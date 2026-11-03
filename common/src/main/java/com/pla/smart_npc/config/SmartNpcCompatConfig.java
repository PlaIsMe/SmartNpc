package com.pla.smart_npc.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SmartNpcCompatConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue YES_STEVE_MODEL_CHANCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("yes_steve_model");
        YES_STEVE_MODEL_CHANCE = builder.comment("Chance to choose a custom server-installed or bundled YSM model instead of YSM Steve/Alex.",
                "0.0 uses YSM Steve/Alex for every NPC, matching the regular skin's wide/slim shape; YSM stays enabled.",
                "1.0 uses custom models, excluding YSM Steve/Alex. If none are usable, Steve/Alex is the fallback.",
                "Intermediate values roll once per NPC. Client-only models are never selected.")
                .defineInRange("modelChance", 1.0D, 0.0D, 1.0D);
        builder.pop();
        SPEC = builder.build();
    }

    private SmartNpcCompatConfig() {
    }
}
