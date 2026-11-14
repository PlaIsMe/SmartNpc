package com.pla.smart_npc.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipInputStream;

/** Called only from the explicit install worker; never executes an archive's contents. */
final class AiRuntimeInstaller {
    private final Path directory;
    private final AiModelDownloader download;
    AiRuntimeInstaller(Path directory, AiModelDownloader download) { this.directory = directory; this.download = download; }
    static void validateEntry(String name) {
        if (name == null || name.isBlank() || name.length() > 240 || name.startsWith("/") || name.contains("\\")
                || name.contains(":") || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Unsafe runtime ZIP path");
        for (String segment : name.split("/")) if (segment.equals("..") || segment.equals(".") || segment.endsWith(".") || segment.endsWith(" ")) throw new IllegalArgumentException("Unsafe runtime ZIP segment");
    }
    Path install(AiModelManifest.Runtime runtime) throws Exception {
        runtime.validate();
        if (runtime.latest()) throw new IllegalArgumentException("Resolve the latest runtime before installation");
        Path base = directory.toAbsolutePath().normalize().resolve("runtimes");
        for (Path ancestor = base; ancestor != null; ancestor = ancestor.getParent()) if (Files.isSymbolicLink(ancestor)) throw new IOException("Runtime directory cannot contain symlinks");
        Files.createDirectories(base);
        Path target = base.resolve(runtime.id() + "-" + runtime.sha256().substring(0, 12));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target)) throw new IOException("Runtime target cannot be a symlink");
            verifyInstalled(target, runtime);
            return target.resolve(runtime.executable());
        }
        Path archive = Files.createTempFile(base, "runtime-", ".zip.part");
        Path staging = Files.createTempDirectory(base, "extract-");
        try {
            download.fetch(runtime.url(), runtime.downloadBytes(), runtime.sha256(), archive);
            download.beginPhase("runtime_extract", 0);
            extract(archive, staging);
            try (var license = AiRuntimeInstaller.class.getResourceAsStream("/META-INF/licenses/llama.cpp.txt")) {
                if (license == null) throw new IOException("Bundled llama.cpp license missing");
                Files.copy(license, staging.resolve("smartnpc-llama.cpp-LICENSE.txt"));
            }
            Path executable = staging.resolve(runtime.executable());
            if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Runtime archive does not contain " + runtime.executable());
            if (!runtime.platform().startsWith("windows") && !executable.toFile().setExecutable(true, true)) throw new IOException("Cannot set runtime executable permission");
            JsonObject receipt = new JsonObject();
            receipt.addProperty("runtime_id", runtime.id());
            receipt.addProperty("download_url", runtime.url());
            receipt.addProperty("archive_sha256", runtime.sha256());
            receipt.addProperty("license", runtime.license());
            JsonObject files = new JsonObject();
            try (var paths = Files.walk(staging)) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) files.addProperty(staging.relativize(file).toString().replace('\\', '/'), hash(file));
            }
            receipt.add("files", files);
            Files.writeString(staging.resolve("smartnpc-runtime-receipt.json"), new Gson().toJson(receipt), StandardOpenOption.CREATE_NEW);
            download.checkCancelled();
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return target.resolve(runtime.executable());
        } finally {
            Files.deleteIfExists(archive);
            if (Files.exists(staging)) try (var paths = Files.walk(staging)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private void verifyInstalled(Path target, AiModelManifest.Runtime runtime) throws Exception {
        Path receiptFile = target.resolve("smartnpc-runtime-receipt.json");
        if (!Files.isRegularFile(receiptFile, LinkOption.NOFOLLOW_LINKS) || Files.size(receiptFile) > 65536) throw new IOException("Runtime receipt missing; remove this installation before reinstalling");
        JsonObject receipt = JsonParser.parseString(Files.readString(receiptFile)).getAsJsonObject();
        if (!runtime.sha256().equalsIgnoreCase(receipt.get("archive_sha256").getAsString())) throw new IOException("Runtime archive identity changed");
        JsonObject files = receipt.getAsJsonObject("files");
        if (files.size() > 512 || !files.has(runtime.executable())) throw new IOException("Invalid runtime receipt");
        for (var file : files.entrySet()) {
            validateEntry(file.getKey());
            Path path = target.resolve(file.getKey());
            for (Path parent = path; parent != null && parent.startsWith(target); parent = parent.getParent()) if (Files.isSymbolicLink(parent)) throw new IOException("Runtime file cannot be a symlink");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !hash(path).equalsIgnoreCase(file.getValue().getAsString())) throw new IOException("Installed runtime was modified; reinstall it");
        }
    }
    int removeOld(String configuredExecutable) throws Exception {
        Path base = directory.toAbsolutePath().normalize().resolve("runtimes");
        for (Path ancestor = base; ancestor != null; ancestor = ancestor.getParent()) if (Files.isSymbolicLink(ancestor)) throw new IOException("Runtime directory cannot contain symlinks");
        Path executable = configuredExecutable.isBlank() ? null : Path.of(configuredExecutable).toAbsolutePath().normalize();
        if (executable == null || !executable.startsWith(base) || base.relativize(executable).getNameCount() < 2
                || !Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Install and configure a managed runtime before removing old versions");
        Path keep = base.resolve(base.relativize(executable).getName(0));
        if (!keep.getFileName().toString().matches("llama-b[0-9]{1,12}-cpu-windows-x64-[a-fA-F0-9]{12}")) throw new IOException("Configured runtime is not a recognized llama.cpp installation");
        long configuredBuild = Long.parseLong(keep.getFileName().toString().split("-")[1].substring(1));
        for (Path ancestor = executable; ancestor != null && ancestor.startsWith(base); ancestor = ancestor.getParent()) if (Files.isSymbolicLink(ancestor)) throw new IOException("Configured runtime cannot contain symlinks");
        if (!executable.toRealPath().startsWith(keep.toRealPath()) || !keep.toRealPath().startsWith(base.toRealPath())) throw new IOException("Configured runtime is outside managed storage");
        int removed = 0;
        try (var directories = Files.list(base)) {
            for (Path target : directories.toList()) {
                if (target.equals(keep) || !target.getParent().equals(base) || Files.isSymbolicLink(target)
                        || !target.getFileName().toString().matches("llama-b[0-9]{1,12}-cpu-windows-x64-[a-fA-F0-9]{12}")) continue;
                if (Long.parseLong(target.getFileName().toString().split("-")[1].substring(1)) >= configuredBuild) continue;
                var paths = removalPaths(target);
                if (paths == null) continue; // Retain manual, modified, incomplete or unrecognized installations.
                // Recheck absolute boundaries immediately before recursive deletion.
                if (!target.toAbsolutePath().normalize().getParent().equals(base) || target.equals(keep) || Files.isSymbolicLink(target)) throw new IOException("Unsafe runtime cleanup target");
                for (Path path : paths.stream().sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(target)) throw new IOException("Runtime cleanup escapes its installation");
                    Files.delete(path);
                }
                removed++;
            }
        }
        return removed;
    }
    private List<Path> removalPaths(Path target) throws Exception {
        Path receiptFile = target.resolve("smartnpc-runtime-receipt.json");
        if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(receiptFile, LinkOption.NOFOLLOW_LINKS) || Files.size(receiptFile) > 65536) return null;
        try {
            JsonObject receipt = JsonParser.parseString(Files.readString(receiptFile)).getAsJsonObject();
            String digest = receipt.get("archive_sha256").getAsString();
            String name = target.getFileName().toString();
            if (!digest.matches("[a-fA-F0-9]{64}") || !name.endsWith("-" + digest.substring(0, 12))) return null;
            String id = name.substring(0, name.length() - 13);
            String tag = id.split("-")[1];
            var runtime = new AiModelManifest.Runtime(id, "windows-x64", "https://github.com/ggml-org/llama.cpp/releases/download/" + tag + "/llama-" + tag + "-bin-win-cpu-x64.zip", 4, digest, "MIT", "llama-server.exe");
            JsonObject files = receipt.getAsJsonObject("files");
            List<Path> paths;
            try (var tree = Files.walk(target, 16)) { paths = tree.limit(1025).toList(); }
            if (paths.size() > 1024) return null;
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(target.getParent().toRealPath())) return null;
            for (Path path : paths) {
                if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(realTarget)) return null;
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (target.relativize(path).getNameCount() >= 16) return null;
                } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !path.equals(receiptFile) && !files.has(target.relativize(path).toString().replace('\\', '/'))) return null;
            }
            verifyInstalled(target, runtime);
            return paths;
        } catch (IOException | RuntimeException error) { return null; }
    }
    void extract(Path archive, Path staging) throws Exception {
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                download.checkCancelled();
                if (++entries > 512) throw new IOException("Runtime ZIP has too many entries");
                validateEntry(entry.getName());
                Path destination = staging.resolve(entry.getName()).normalize();
                if (!destination.startsWith(staging) || destination.equals(staging)) throw new IOException("Runtime ZIP escapes extraction directory");
                if (entry.isDirectory()) { Files.createDirectories(destination); continue; }
                Files.createDirectories(destination.getParent());
                try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        download.checkCancelled();
                        expanded += read;
                        if (expanded > 512L * 1024 * 1024) throw new IOException("Runtime ZIP expands beyond 512 MiB");
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }
    private String hash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) { download.checkCancelled(); digest.update(buffer, 0, read); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
