package com.pla.smart_npc.ai;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Presentation roster only. Unpublished tiers never acquire invented download metadata. */
public final class AiModelTiers {
    public record Tier(String family, String name, String tier, String icon, String ram) {}
    private static final List<Tier> TIERS = load();
    private AiModelTiers() {}
    public static List<Tier> all() { return TIERS; }
    private static List<Tier> load() {
        try (var stream = AiModelTiers.class.getResourceAsStream("/data/smart_npc/ai/tiers.json")) {
            if (stream == null) throw new IllegalStateException("Missing model tiers");
            Tier[] tiers = new Gson().fromJson(new String(stream.readAllBytes(), StandardCharsets.UTF_8), Tier[].class);
            if (tiers.length != 2 || Arrays.stream(tiers).map(Tier::family).distinct().count() != tiers.length) throw new IllegalStateException("Expected two distinct Qwen model tiers");
            for (Tier tier : tiers) {
                if (!tier.family().matches("[a-z0-9.-]+") || !tier.icon().matches("[a-z_0-9]+") || tier.ram() == null || tier.ram().length() > 40) throw new IllegalStateException("Invalid model tier");
            }
            return List.of(tiers);
        } catch (IOException error) { throw new IllegalStateException("Cannot read model tiers", error); }
    }
}
