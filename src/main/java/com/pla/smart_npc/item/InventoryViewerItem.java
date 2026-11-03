package com.pla.smart_npc.item;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.network.PlayerNpcInspectorData;
import com.pla.smart_npc.network.PlayerNpcInspectorPacket;
import com.pla.smart_npc.network.PlayerNpcInspectatorModePacket;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

public class InventoryViewerItem extends Item {
    public InventoryViewerItem(Properties properties) {
        super(properties);
    }

    @Override
    public @NotNull InteractionResult interactLivingEntity(
            @NotNull ItemStack stack,
            @NotNull Player player,
            @NotNull LivingEntity target,
            @NotNull InteractionHand hand
    ) {
        if (!(target instanceof PlayerNpcEntity playerNpcEntity)) {
            if (!player.level().isClientSide()) {
                player.displayClientMessage(Component.translatable("message.player_npc.inspector.unsupported")
                        .withStyle(ChatFormatting.GRAY), false);
            }
            return player.level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(
                    serverPlayer,
                    new PlayerNpcInspectorPacket(
                            target.getId(),
                            PlayerNpcInspectorData.createSnapshot(playerNpcEntity),
                            PlayerNpcInspectorData.createBuildStatusText(playerNpcEntity),
                            PlayerNpcInspectorData.createPerformanceText(),
                            PlayerNpcInspectorData.createDailyJobText(playerNpcEntity),
                            // The client requests this separately only while its requirements panel is visible.
                            "",
                            PlayerNpcInspectorData.createTeamInfo(playerNpcEntity),
                            PlayerNpcGoalTraceLogger.isEffectivelyTracing(serverPlayer, playerNpcEntity)
                    )
            );
        }

        return player.level().isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public @NotNull InteractionResult use(@NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            boolean overall = player.isShiftKeyDown();
            if (overall && PlayerNpcInspectatorModePacket.isInspectatorActive(serverPlayer)) {
                PlayerNpcInspectatorModePacket.restorePlayer(serverPlayer);
            }
            PacketDistributor.sendToPlayer(
                    serverPlayer,
                    overall
                            ? PlayerNpcInspectorPacket.overall(
                                    PlayerNpcInspectorData.createAiResourceText(serverPlayer.level().getServer(), null))
                            : PlayerNpcInspectorPacket.clear()
            );
        }
        return level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(
            @NotNull ItemStack stack,
            @NotNull Item.TooltipContext context,
            @NotNull TooltipDisplay display,
            @NotNull Consumer<Component> tooltip,
            @NotNull TooltipFlag flag
    ) {
        super.appendHoverText(stack, context, display, tooltip, flag);
        tooltip.accept(Component.translatable("tooltip.player_npc.player_npc_inspector").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatable("tooltip.player_npc.player_npc_inspector.inspectator").withStyle(ChatFormatting.DARK_AQUA));
    }

}
