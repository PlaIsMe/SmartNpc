package com.pla.smart_npc.client;

import com.pla.smart_npc.entity.FishingRodCastState;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

public final class SmartNpcClientItemProperties {
    private SmartNpcClientItemProperties() {
    }

    public static void register() {
        ResourceLocation castProperty = ResourceLocation.fromNamespaceAndPath("minecraft", "cast");
        ItemPropertyFunction originalCast = ItemProperties.getProperty(Items.FISHING_ROD.getDefaultInstance(), castProperty);
        ItemProperties.register(Items.FISHING_ROD, castProperty, (stack, level, entity, seed) -> {
            if (entity instanceof FishingRodCastState castState) {
                boolean mainHand = entity.getMainHandItem() == stack && castState.isFishingRodCast(InteractionHand.MAIN_HAND);
                boolean offhand = entity.getOffhandItem() == stack && castState.isFishingRodCast(InteractionHand.OFF_HAND);
                return mainHand || offhand ? 1.0F : 0.0F;
            }
            return originalCast == null ? 0.0F : originalCast.call(stack, level, entity, seed);
        });
    }
}
