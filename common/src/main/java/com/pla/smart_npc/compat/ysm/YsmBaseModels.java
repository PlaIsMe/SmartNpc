package com.pla.smart_npc.compat.ysm;

/** Built-in identities used by YSM 2.6.5's player-skin texture resolver on both loaders. */
public final class YsmBaseModels {
    public static final String STEVE = "misc/2_steve";
    public static final String ALEX = "misc/1_alex";

    private YsmBaseModels() {
    }

    public static boolean isBaseModel(String modelId) {
        return STEVE.equals(modelId) || ALEX.equals(modelId);
    }
}
