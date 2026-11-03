package com.pla.smart_npc.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class SmartNpcCompatConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.DoubleValue YES_STEVE_MODEL_CHANCE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("yes_steve_model");
        YES_STEVE_MODEL_CHANCE = builder.comment("Chance for a newly spawned NPC to use one server-installed YSM model (1.0 = 100%).",
                "0.0 disables YSM appearance and emotes, including existing NPCs; saved choices are retained.",
                "Changing a nonzero chance does not reroll existing NPCs. Client-only models are never selected.")
                .defineInRange("modelChance", 1.0D, 0.0D, 1.0D);
        builder.pop();
        SPEC = builder.build();
    }

    private SmartNpcCompatConfig() {
    }
}
