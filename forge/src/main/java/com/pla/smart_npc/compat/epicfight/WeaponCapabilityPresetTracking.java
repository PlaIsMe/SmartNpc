package com.pla.smart_npc.compat.epicfight;

import com.google.common.collect.MapMaker;
import net.minecraft.world.item.Item;
import yesman.epicfight.world.capabilities.item.CapabilityItem;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/** Remembers the actual factory used, since Epic Fight capabilities do not retain their preset. */
public final class WeaponCapabilityPresetTracking {
    // Weak identity keys keep reloads and temporary builders from retaining old capabilities.
    private static final ConcurrentMap<Object, Function<Item, CapabilityItem.Builder>> PRESETS =
            new MapMaker().weakKeys().makeMap();

    private WeaponCapabilityPresetTracking() {
    }

    public static CapabilityItem.Builder recordBuilder(
            CapabilityItem.Builder builder, Function<Item, CapabilityItem.Builder> preset) {
        PRESETS.put(builder, preset);
        return builder;
    }

    public static void recordBuiltCapability(CapabilityItem.Builder builder, CapabilityItem capability) {
        Function<Item, CapabilityItem.Builder> preset = PRESETS.get(builder);
        if (preset != null) {
            PRESETS.put(capability, preset);
        }
    }

    /** Unknown/custom construction paths deliberately do not match a built-in preset. */
    public static Function<Item, CapabilityItem.Builder> getPreset(CapabilityItem capability) {
        return PRESETS.get(capability);
    }
}
