package com.pla.smart_npc.compat.ysm;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Filename aliases for the pinned YSM server registry; never reads client model storage. */
final class YsmModelFileIndex {
    private YsmModelFileIndex() {
    }

    static Map<String, String> build(Collection<String> serverModelIds) {
        Map<String, String> result = new HashMap<>();
        for (String modelId : serverModelIds) {
            // YSM 2.6.5 registers encrypted files with the .ysm extension, including
            // legacy containers whose embedded checksum differs from metadata.hash.
            if (!modelId.toLowerCase(Locale.ROOT).endsWith(".ysm")) {
                continue;
            }
            int nameStart = Math.max(modelId.lastIndexOf('/'), modelId.lastIndexOf('\\')) + 1;
            String filename = modelId.substring(nameStart, modelId.length() - 4);
            if (!filename.isEmpty()) {
                // Equal basenames in separate packs/folders must not silently choose one.
                result.merge(filename, modelId, (first, second) -> first.equals(second) ? first : "");
            }
        }
        return Map.copyOf(result);
    }
}
