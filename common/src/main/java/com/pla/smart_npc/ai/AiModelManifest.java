package com.pla.smart_npc.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

/** Administrator-reviewed model metadata. Weights are separate, optional downloads. */
public record AiModelManifest(int schema, List<Model> models, List<Runtime> runtimes, int catalogRevision) {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public AiModelManifest(int schema, List<Model> models) { this(schema, models, defaultRuntimes(), 5); }
    public AiModelManifest(int schema, List<Model> models, List<Runtime> runtimes) { this(schema, models, runtimes, 5); }
    public record Runtime(String id, String platform, String url, long downloadBytes, String sha256, String license, String executable, String source) {
        public Runtime(String id, String platform, String url, long downloadBytes, String sha256, String license, String executable) {
            this(id, platform, url, downloadBytes, sha256, license, executable, null);
        }
        public boolean latest() { return "llama.cpp-latest".equals(source); }
        public void validate() {
            if (id == null || !id.matches("[a-zA-Z0-9._-]{1,80}") || id.contains("..")
                    || platform == null || !List.of("windows-x64", "windows-arm64", "linux-x64", "linux-arm64", "macos-arm64", "macos-x64").contains(platform)) throw new IllegalArgumentException("Invalid runtime identity/platform");
            AiRuntimeInstaller.validateEntry(executable);
            if (source != null) {
                if (!latest() || !platform.equals("windows-x64") || !executable.equals("llama-server.exe")) throw new IllegalArgumentException("Unsupported automatic runtime source/platform");
                return;
            }
            if (downloadBytes < 4 || downloadBytes > 512L * 1024 * 1024
                    || sha256 == null || !sha256.matches("[a-fA-F0-9]{64}") || license == null || license.isBlank() || license.length() > 240) throw new IllegalArgumentException("Invalid runtime metadata");
            AiModelDownloader.validateUri(URI.create(url));
        }
    }
    public Runtime runtimeToInstall(AiConfig config, Path directory) {
        if (config.externalRuntime) return null;
        EmbeddedLlamaBridge.requirePlatform();
        return null; // Native library ships in the mod; only GGUF models are downloaded.
    }
    public Runtime runtimeForPlatform() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);
        String platform = (os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux") + "-" + (List.of("amd64", "x86_64").contains(arch) ? "x64" : List.of("aarch64", "arm64").contains(arch) ? "arm64" : arch);
        return runtimes.stream().filter(runtime -> runtime.platform().equals(platform)).findFirst().orElseThrow(() -> new IllegalArgumentException("No runtime configured for " + platform + "; add a matching ZIP to models.json runtimes or set executable manually"));
    }
    public record Model(String id, String name, String file, String url, long downloadBytes, String sha256, String license, String engineRequirement, String description, String ramEstimate, String language, String family) {
        public Model(String id, String name, String file, String url, long downloadBytes, String sha256, String license, String engineRequirement, String description, String ramEstimate, String language) {
            this(id, name, file, url, downloadBytes, sha256, license, engineRequirement, description, ramEstimate, language, null);
        }
        public Model(String id, String name, String file, String url, long downloadBytes, String sha256, String license, String engineRequirement, String description, String ramEstimate) {
            this(id, name, file, url, downloadBytes, sha256, license, engineRequirement, description, ramEstimate, "en_us");
        }
        public Model(String id, String name, String file, String url, long downloadBytes, String sha256, String license, String engineRequirement) {
            this(id, name, file, url, downloadBytes, sha256, license, engineRequirement, "", "");
        }
        public void requireSupportedLanguage() {
            if (!List.of("en_us", "zh_cn", "vi_vn").contains(locale())) throw new IllegalArgumentException("This model language release is not supported yet.");
        }
        public String locale() { return language == null ? "en_us" : language; }
        public String familyId() { return family == null ? id : family; }
        public void validate() {
            if (language != null && !List.of("en_us", "vi_vn", "zh_cn").contains(language)) throw new IllegalArgumentException("Invalid model language");
            if (AiSpeechStyle.unfiltered(id) && !locale().equals("en_us")) throw new IllegalArgumentException("Unfiltered English release must use en_us");
            if (family != null && (!family.matches("[a-zA-Z0-9._-]{1,80}") || family.contains(".."))) throw new IllegalArgumentException("Invalid model family");
            if (id == null || !id.matches("[a-zA-Z0-9._-]{1,80}") || name == null || name.isBlank() || name.length() > 120
                    || license == null || license.isBlank() || license.length() > 240
                    || engineRequirement == null || engineRequirement.isBlank() || engineRequirement.length() > 240
                    || sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")
                    || downloadBytes < 4 || downloadBytes > 8L * 1024 * 1024 * 1024) throw new IllegalArgumentException("Invalid model manifest metadata");
            if (description != null && description.length() > 160 || ramEstimate != null && ramEstimate.length() > 160) throw new IllegalArgumentException("Model description/RAM text too long");
            validateFileName(file);
            AiModelDownloader.validateUri(URI.create(url));
        }
    }

    public static AiModelManifest load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("models.json");
        if (!Files.exists(path)) {
            AiModelManifest defaults = defaults();
            Files.writeString(path, JSON.toJson(defaults), StandardCharsets.UTF_8);
            return defaults;
        }
        if (Files.size(path) > 65536) throw new IOException("models.json exceeds 64 KiB");
        try {
            AiModelManifest result = JSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), AiModelManifest.class);
            if (result == null || result.schema != 1 || result.models == null || result.models.size() > 32) throw new IllegalArgumentException("Invalid manifest version or model list");
            HashSet<String> ids = new HashSet<>();
            for (Model model : result.models) {
                model.validate();
                if (!ids.add(model.id)) throw new IllegalArgumentException("Duplicate model ID");
            }
            AiModelManifest bundled = defaults();
            List<Model> models = new java.util.ArrayList<>(result.models);
            if (result.catalogRevision < 2) {
                for (Model model : defaults().models()) {
                    if (ids.add(model.id())) models.add(model);
                    else for (int index = 0; index < models.size(); index++) {
                        Model current = models.get(index);
                        if (current.id().equals(model.id()) && current.sha256().equals(model.sha256())) {
                            models.set(index, new Model(current.id(), current.name(), current.file(), current.url(), current.downloadBytes(), current.sha256(), current.license(), current.engineRequirement(),
                                    current.description() == null ? model.description() : current.description(), current.ramEstimate() == null ? model.ramEstimate() : current.ramEstimate(), current.language(), current.family()));
                        }
                    }
                }
                if (models.size() > 32) throw new IllegalArgumentException("Too many models after catalog migration");
            }
            if (result.catalogRevision < bundled.catalogRevision()) {
                // Retired built-in families must not reappear from an older instance catalog.
                // Administrator-provided URLs remain editable and are preserved.
                models.removeIf(model -> List.of("minicpm5-1b", "lfm3-1.7b").contains(model.familyId()) && isBuiltInRelease(model));
                // Built-in release tags are maintained in place. Refresh their verified asset
                // metadata on a mod update; custom URLs and unrelated IDs remain admin-owned.
                for (Model model : bundled.models()) {
                    if (ids.add(model.id())) models.add(model);
                    else for (int index = 0; index < models.size(); index++) {
                        Model current = models.get(index);
                        if (current.id().equals(model.id()) && isBuiltInRelease(current) && isBuiltInRelease(model)
                                && current.locale().equals(model.locale()) && current.familyId().equals(model.familyId())) {
                            models.set(index, new Model(current.id(), current.name(), model.file(), model.url(), model.downloadBytes(), model.sha256(), model.license(), model.engineRequirement(), current.description(), maintainedRam(current.ramEstimate()) ? model.ramEstimate() : current.ramEstimate(), current.language(), current.family()));
                        }
                    }
                }
                if (models.size() > 32) throw new IllegalArgumentException("Too many models after catalog migration");
            }
            List<Runtime> runtimes = new java.util.ArrayList<>(result.runtimes == null ? defaultRuntimes() : result.runtimes);
            boolean migratedRuntime = false;
            if (result.catalogRevision < 3) {
                for (int index = 0; index < runtimes.size(); index++) {
                    Runtime current = runtimes.get(index);
                    // Only migrate the historical bundled entry; retain custom pinned runtimes.
                    if (current != null && current.source() == null && "windows-x64".equals(current.platform())
                            && List.of("llama-b12144-cpu-windows-x64", "llama-b12176-cpu-windows-x64").contains(current.id())
                            && "569990fcbacbe305aef41490f71df70088474a59d514ae3b1ee65c130ee74555".equals(current.sha256())
                            && ("https://github.com/ggml-org/llama.cpp/releases/download/" + current.id().split("-")[1] + "/llama-" + current.id().split("-")[1] + "-bin-win-cpu-x64.zip").equals(current.url())
                            && "llama-server.exe".equals(current.executable())) {
                        runtimes.set(index, defaultRuntimes().stream().filter(Runtime::latest).findFirst().orElseThrow());
                        migratedRuntime = true;
                    }
                }
            }
            if (runtimes.size() > 16) throw new IllegalArgumentException("Too many runtimes");
            ids.clear();
            for (Runtime runtime : runtimes) {
                runtime.validate();
                if (!ids.add(runtime.id())) throw new IllegalArgumentException("Duplicate runtime ID");
            }
            AiModelManifest normalized = new AiModelManifest(1, List.copyOf(models), List.copyOf(runtimes), Math.max(bundled.catalogRevision(), result.catalogRevision));
            if (result.runtimes == null || result.catalogRevision < bundled.catalogRevision() || migratedRuntime) Files.writeString(path, JSON.toJson(normalized), StandardCharsets.UTF_8);
            return normalized;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid models.json: " + exception.getMessage(), exception);
        }
    }

    public Model find(String id) {
        return models.stream().filter(model -> model.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown model ID"));
    }

    /** Refresh shipped measurements/estimates while preserving custom administrator descriptions. */
    private static boolean maintainedRam(String text) {
        return text == null || text.equals("Not measured") || text.matches("[0-9]+(?:\\.[0-9]+)? MiB \\(measured native peak\\)")
                || List.of("0.6-1.2 GiB (estimate)", "0.8-1.5 GiB (estimate)", "0.9-1.8 GiB (estimate)", "1-2 GiB (estimate)",
                        "1-2 GiB (estimate; depends on context/runtime)", "1-2 GiB (estimate; measured about 1.0 GiB on test CPU)").contains(text);
    }

    /** Recognize historical per-model and current per-language tags without claiming custom URLs. */
    static boolean isBuiltInRelease(Model model) {
        URI uri = URI.create(model.url());
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
        String prefix = "/PlaIsMe/SmartNpc/releases/download/";
        String path = uri.getRawPath();
        if (!path.startsWith(prefix)) return false;
        String[] parts = path.substring(prefix.length()).split("/", -1);
        if (parts.length != 2 || !parts[1].equals(model.file())) return false;
        if (parts[0].equals(model.id())) return true;
        String localeTag = "llm-" + model.locale().replace('_', '-');
        List<String> tagPrefixes = AiSpeechStyle.unfiltered(model.id()) ? List.of(localeTag + "-v", localeTag + "-unfiltered-v") : List.of(localeTag + "-v");
        return tagPrefixes.stream().anyMatch(prefixTag -> parts[0].startsWith(prefixTag)
                && parts[0].substring(prefixTag.length()).matches("[0-9]+(?:\\.[0-9]+)*"));
    }

    public static AiModelManifest defaults() {
        // Release maintainers sync this catalog; custom URLs in instance catalogs remain admin-owned.
        try (var input = AiModelManifest.class.getResourceAsStream("/data/smart_npc/ai/downloads.json")) {
            if (input == null) throw new IllegalStateException("Bundled AI downloads catalog is missing");
            AiModelManifest catalog = JSON.fromJson(new String(input.readAllBytes(), StandardCharsets.UTF_8), AiModelManifest.class);
            if (catalog == null || catalog.schema != 1 || catalog.models == null || catalog.runtimes == null) throw new IllegalStateException("Invalid bundled AI downloads catalog");
            catalog.models.forEach(Model::validate);
            catalog.runtimes.forEach(Runtime::validate);
            return catalog;
        } catch (IOException error) { throw new IllegalStateException("Cannot read bundled AI downloads catalog", error); }
    }
    private static List<Runtime> defaultRuntimes() {
        return defaults().runtimes();
    }

    public static void validateFileName(String file) {
        if (file == null || !file.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,150}\\.gguf") || file.contains("..")) throw new IllegalArgumentException("Model filename must be a plain .gguf basename");
    }

    public static Path modelPath(Path directory, String file) throws IOException {
        validateFileName(file);
        Path base = directory.toAbsolutePath().normalize();
        // Reject symlinks at every existing ancestor; resolve the final target only inside models/.
        for (Path path = base; path != null; path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("AI model directory must not contain symlinks");
        }
        Path models = base.resolve("models");
        if (Files.isSymbolicLink(models)) throw new IOException("AI models directory must not be a symlink");
        Files.createDirectories(models);
        Path target = models.resolve(file).normalize();
        if (!target.getParent().equals(models) || Files.isSymbolicLink(target)) throw new IOException("Unsafe AI model destination");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Model target is not a regular file");
        return target;
    }
}
