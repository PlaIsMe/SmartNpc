package com.pla.smart_npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;

/** Resolves an automatic channel to immutable asset metadata on the confirmed download worker. */
final class AiRuntimeReleaseResolver {
    private AiRuntimeReleaseResolver() {}

    static AiModelManifest.Runtime resolve(AiModelManifest.Runtime channel, AiModelDownloader downloader) throws Exception {
        channel.validate();
        if (!channel.latest()) return channel;
        return select(channel, downloader.llamaReleases());
    }

    static AiModelManifest.Runtime select(AiModelManifest.Runtime channel, JsonArray releases) throws IOException {
        channel.validate();
        if (!channel.latest()) throw new IllegalArgumentException("Expected automatic llama.cpp runtime channel");
        AiModelManifest.Runtime newest = null;
        long newestBuild = -1;
        for (var element : releases) {
            JsonObject release = element.getAsJsonObject();
            if (release.get("draft").getAsBoolean()) continue;
            String tag = release.get("tag_name").getAsString();
            if (!tag.matches("b[0-9]{1,12}")) continue;
            long build = Long.parseLong(tag.substring(1));
            if (build <= newestBuild) continue;
            // Upstream's b-builds are prereleases; /releases/latest can be a wrapper without binaries.
            String name = "llama-" + tag + "-bin-win-cpu-x64.zip";
            for (var value : release.getAsJsonArray("assets")) {
                JsonObject asset = value.getAsJsonObject();
                if (!name.equals(asset.get("name").getAsString())) continue;
                if (!"uploaded".equals(asset.get("state").getAsString())) continue;
                if (asset.get("digest") == null || asset.get("digest").isJsonNull()) throw new IOException("Latest llama.cpp asset has no SHA-256 digest; retry when the release is complete");
                String digest = asset.get("digest").getAsString();
                if (!digest.matches("sha256:[a-fA-F0-9]{64}")) throw new IOException("Latest llama.cpp asset has an invalid SHA-256 digest");
                String url = asset.get("browser_download_url").getAsString();
                if (!url.equals("https://github.com/ggml-org/llama.cpp/releases/download/" + tag + "/" + name)) throw new IOException("Unexpected llama.cpp release asset URL");
                newest = new AiModelManifest.Runtime("llama-" + tag + "-cpu-windows-x64", channel.platform(), url,
                        asset.get("size").getAsLong(), digest.substring(7), "MIT llama.cpp; includes LLVM OpenMP notices; https://github.com/ggml-org/llama.cpp/blob/" + tag + "/LICENSE", channel.executable());
                newest.validate();
                newestBuild = build;
                break;
            }
        }
        if (newest == null) throw new IOException("No recent llama.cpp Windows CPU x64 ZIP found; retry later or configure a pinned runtime");
        return newest;
    }
}
