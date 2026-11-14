package com.pla.smart_npc.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/** JNI library shipped in the mod jar, extracted only because System.load needs a real file. */
final class EmbeddedLlamaBridge {
    static final String VERSION = "embedded llama.cpp";
    private static final String RESOURCE = "/META-INF/natives/smartnpc/windows-x64/";
    private static boolean loaded;

    private EmbeddedLlamaBridge() {}

    static void requirePlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.startsWith("windows") || !List.of("amd64", "x86_64").contains(arch)) {
            throw new IllegalArgumentException("Bundled AI supports Windows x64 (AVX2). Use Legacy chat or an explicitly configured external runtime on this platform.");
        }
    }

    static synchronized void load(Path directory) throws IOException {
        if (loaded) return;
        requirePlatform();
        Path base = directory.toAbsolutePath().normalize().resolve("native").resolve("windows-x64");
        // Refuse redirected native storage; never load a library supplied by a custom model catalog.
        for (Path ancestor = base; ancestor != null; ancestor = ancestor.getParent()) {
            if (Files.isSymbolicLink(ancestor)) throw new IOException("Native runtime storage cannot use symbolic links");
        }
        Files.createDirectories(base);
        Path library = base.resolve("smartnpc_llama.dll");
        Path temporary = Files.createTempFile(base, "smartnpc_llama-", ".dll");
        try {
            try (var input = EmbeddedLlamaBridge.class.getResourceAsStream(RESOURCE + "smartnpc_llama.dll")) {
                if (input == null) throw new IOException("Bundled native runtime is missing");
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            // Compare directly with the jar's bytes; no separate checksum or runtime manifest.
            if (!Files.isRegularFile(library, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(library, temporary) != -1) {
                try {
                    Files.move(temporary, library, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (FileSystemException locked) {
                    // Another Minecraft instance may still have the old DLL mapped on Windows.
                    library = temporary;
                    library.toFile().deleteOnExit();
                    temporary = null;
                }
            }
        } finally { if (temporary != null) Files.deleteIfExists(temporary); }
        try { System.load(library.toAbsolutePath().toString()); }
        catch (UnsatisfiedLinkError error) { throw new IOException("Cannot load bundled llama.cpp native library: " + error.getMessage(), error); }
        loaded = true;
    }

    // Actor-owned handles never escape the provider. UTF-8 byte arrays preserve emoji/CJK;
    // JNI's modified-UTF-8 String API would corrupt non-BMP characters.
    static native long open(byte[] path, int contextSize, int threads, int timeoutSeconds, Thread owner) throws Exception;
    static native byte[] generate(long handle, byte[] system, byte[] user, byte[] schema, int maxTokens, double temperature, int timeoutSeconds, Thread owner) throws Exception;
    static native void close(long handle);
}
