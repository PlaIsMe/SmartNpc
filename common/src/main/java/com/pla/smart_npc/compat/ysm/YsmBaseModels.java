package com.pla.smart_npc.compat.ysm;
public final class YsmBaseModels {
    public static final String STEVE = "misc/2_steve";
    public static final String ALEX = "misc/1_alex";
    private YsmBaseModels() {}
    public static boolean isBaseModel(String model) { return STEVE.equals(model) || ALEX.equals(model); }
}
