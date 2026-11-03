package com.pla.smart_npc.item;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.network.PlayerNpcInspectorData;
import com.pla.smart_npc.network.PlayerNpcInspectorPacket;
import com.pla.smart_npc.network.PlayerNpcInspectatorModePacket;
import com.pla.smart_npc.network.SmartNpcNetwork;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class InventoryViewerItem extends Item {
    public InventoryViewerItem() {
        super(new Properties().stacksTo(1));
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
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResult.sidedSuccess(player.level().isClientSide());
        }

        if (player instanceof ServerPlayer serverPlayer) {
            SmartNpcNetwork.CHANNEL.sendToPlayer(serverPlayer,
                    new PlayerNpcInspectorPacket(
                            target.getId(),
                            PlayerNpcInspectorData.createSnapshot(playerNpcEntity),
                            PlayerNpcInspectorData.createBuildStatusText(playerNpcEntity),
                            PlayerNpcInspectorData.createPerformanceText(),
                            PlayerNpcInspectorData.createDailyJobText(playerNpcEntity),
                            PlayerNpcInspectorData.createBuildRequirementsText(playerNpcEntity),
                            PlayerNpcInspectorData.createTeamInfo(playerNpcEntity),
                            PlayerNpcGoalTraceLogger.isEffectivelyTracing(serverPlayer, playerNpcEntity)
                    )
            );
        }

        return InteractionResult.sidedSuccess(player.level().isClientSide());
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player, @NotNull InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            boolean overall = player.isShiftKeyDown();
            if (overall && PlayerNpcInspectatorModePacket.isInspectatorActive(serverPlayer)) {
                PlayerNpcInspectatorModePacket.restorePlayer(serverPlayer);
            }
            SmartNpcNetwork.CHANNEL.sendToPlayer(serverPlayer,
                    overall
                            ? PlayerNpcInspectorPacket.overall(
                                    PlayerNpcInspectorData.createAiResourceText(serverPlayer.server, null))
                            : PlayerNpcInspectorPacket.clear()
            );
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(
            @NotNull ItemStack stack,
            Level level,
            @NotNull List<Component> tooltip,
            @NotNull TooltipFlag flag
    ) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.player_npc.player_npc_inspector").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.player_npc.player_npc_inspector.inspectator").withStyle(ChatFormatting.DARK_AQUA));
    }

}
