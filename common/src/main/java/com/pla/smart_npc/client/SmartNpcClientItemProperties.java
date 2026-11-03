package com.pla.smart_npc.client;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import dev.architectury.registry.item.ItemPropertiesRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Items;

public final class SmartNpcClientItemProperties {
    private SmartNpcClientItemProperties() {
    }

    public static void register() {
        ItemPropertiesRegistry.register(Items.FISHING_ROD, new ResourceLocation("minecraft", "cast"), (stack, level, entity, seed) -> {
            if (entity instanceof PlayerNpcEntity playerNpc) {
                boolean mainHand = playerNpc.getMainHandItem() == stack;
                boolean offhand = playerNpc.getOffhandItem() == stack;
                boolean fishing = "ai.player_npc.fishing".equals(playerNpc.getCurrentAiState())
                        || "ai.player_npc.combat_fishing".equals(playerNpc.getCurrentAiState());
                return fishing && (mainHand || offhand) ? 1.0F : 0.0F;
            }
            if (!(entity instanceof Player player)) {
                return 0.0F;
            }

            boolean mainHand = player.getMainHandItem() == stack;
            boolean offhand = player.getOffhandItem() == stack;
            if (player.getMainHandItem().getItem() instanceof FishingRodItem) {
                offhand = false;
            }
            return (mainHand || offhand) && player.fishing != null ? 1.0F : 0.0F;
        });
    }
}
