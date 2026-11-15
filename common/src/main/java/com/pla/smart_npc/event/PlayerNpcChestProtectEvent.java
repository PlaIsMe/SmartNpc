package com.pla.smart_npc.event;

import com.pla.smart_npc.platform.event.NpcEvents.*;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.ai.AiChatService;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import com.pla.smart_npc.util.PlayerNpcChatTemplateLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import java.util.List;

/** Server-side reactions to another living entity disturbing an NPC's tracked chest. */
public final class PlayerNpcChestProtectEvent {
    private PlayerNpcChestProtectEvent() {
    }
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()
                || !(event.getLevel() instanceof ServerLevel serverLevel)
                || !serverLevel.getBlockState(event.getPos()).is(Blocks.CHEST)) {
            return;
        }
        reportOffense(serverLevel, event.getPos(), event.getEntity(), "opened");
    }
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()
                || !(event.getLevel() instanceof ServerLevel serverLevel)
                || !event.getState().is(Blocks.CHEST)) {
            return;
        }
        reportOffense(serverLevel, event.getPos(), event.getPlayer(), "broke");
    }

    public static void reportOffense(ServerLevel serverLevel, BlockPos chestPos, LivingEntity offender, String action) {
        if (serverLevel == null || chestPos == null || offender == null || !offender.isAlive()) {
            return;
        }

        // Never load chunks for protection, but do not impose an arbitrary distance limit on an
        // owner that is already loaded and ticking in this level.
        for (var entity : serverLevel.getAllEntities()) {
            if (!(entity instanceof PlayerNpcEntity owner)
                    || !owner.isAlive()
                    || owner == offender || !owner.isOwnedChest(chestPos)) continue;
            AiChatService service = AiChatService.get(serverLevel.getServer());
            if (owner.distanceToSqr(offender) <= 256 && owner.hasLineOfSight(offender))
                ChatUtil.legacyReaction(owner, action.equals("broke") ? PlayerNpcChatTemplateLoader.OWNED_CHEST_BROKEN : PlayerNpcChatTemplateLoader.OWNED_CHEST_OPENED, offender, 3, offender.getDisplayName());
            if (service != null && owner.distanceToSqr(offender) <= 256 && owner.hasLineOfSight(offender) && owner.getRandom().nextInt(3) == 0)
                service.contextualSpeech(owner, action.equals("broke") ? "owned_chest_broken" : "owned_chest_opened", offender, List.of(offender.getName().getString() + " " + action + " your own tracked chest."));
            if (owner.isTeamFollower()
                    || owner.isTeamAlliedWith(offender)
                    || !owner.hasInterest(PlayerNpcInterest.CHEST_PROTECT)
                    || !owner.isOwnedChest(chestPos)) {
                continue;
            }
            owner.setChestProtectionTarget(offender);
            owner.wakeUpIdleWork();
            owner.setCurrentAiState("ai.player_npc.protecting_chest");
            owner.setCurrentAiDetail("owned chest " + action + " by " + offender.getDisplayName().getString());
        }
    }
}
