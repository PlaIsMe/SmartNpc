package com.pla.smart_npc.ai;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import java.util.EnumSet;
import java.util.Set;

public final class AiActionGuard {
    private AiActionGuard() {}
    public static boolean safe(PlayerNpcEntity npc) {
        return npc.isAlive() && !npc.isRemoved() && !npc.isNoAi() && !npc.isSleeping() && !npc.isPassenger()
                && !npc.isHealing() && !npc.isUsingItem() && !npc.isAggressive() && npc.hurtTime == 0
                && npc.getTarget() == null && !npc.isOnFire() && !npc.isInLava() && !npc.isInWall()
                && !npc.isInWater() && npc.onGround() && npc.getUpwardEscapeTarget() == null && !npc.isTeamUpRequestPending();
    }
    public static boolean authorized(PlayerNpcEntity npc, ServerPlayer player) {
        return player != null && (player.hasPermissions(2)
                || npc.isTeamLeaderPlayer() && player.getUUID().equals(npc.getTeamLeaderUuid()));
    }
    public static Set<AiAction.Type> allowed(PlayerNpcEntity npc, ServerPlayer player, AiConfig config) {
        EnumSet<AiAction.Type> result = EnumSet.of(AiAction.Type.NONE);
        if (!config.actionsEnabled || !safe(npc) || player == null) return result;
        result.addAll(EnumSet.of(AiAction.Type.SOCIAL_LOOK_AT, AiAction.Type.REQUEST_HELP, AiAction.Type.ASK_PLAYER_FOR_ITEM, AiAction.Type.SOCIAL_EMOTE));
        if (config.harmlessTrolling) result.add(AiAction.Type.TROLL_SWING);
        if (config.giftsEnabled && player.distanceToSqr(npc) <= 16) result.add(AiAction.Type.GIVE_ITEM);
        if (!authorized(npc, player)) return result;
        result.addAll(EnumSet.of(AiAction.Type.STOP_CURRENT_GOAL, AiAction.Type.RESUME_PREVIOUS_GOAL, AiAction.Type.APPROACH_ENTITY));
        if (npc.isTeamLeaderPlayer() && player.getUUID().equals(npc.getTeamLeaderUuid())) result.add(AiAction.Type.FOLLOW_PLAYER);
        if (npc.getSelectedDailyJobInterest().isPresent()) result.add(AiAction.Type.GATHER_ITEM);
        if (npc.getSelectedDailyJobInterest().orElse(null) == PlayerNpcInterest.MINING && hasPickaxe(npc)) result.add(AiAction.Type.MINE_RESOURCE);
        return result;
    }
    public static String validate(PlayerNpcEntity npc, ServerPlayer player, AiAction action, AiConfig config, Set<AiAction.Type> offered) {
        if (!offered.contains(action.type()) || !allowed(npc, player, config).contains(action.type())) return "The proposed action is not currently allowed.";
        if (action.target() != null && !action.target().equals("PLAYER") && !action.target().equals(player.getUUID().toString())) return "The requested target is outside this conversation.";
        if (action.type() == AiAction.Type.GIVE_ITEM) {
            if (NpcGoalBridge.giftCandidates(npc, action, config).isEmpty()) return "No carried items satisfy the requested gift and transfer limits.";
        }
        if (action.type() == AiAction.Type.GATHER_ITEM) {
            ResourceLocation id = ResourceLocation.tryParse(action.item() == null ? "" : action.item());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id) || !new ItemStack(BuiltInRegistries.ITEM.get(id)).is(ItemTags.LOGS)
                    || action.count() < 1 || action.count() > 32) return "Gathering supports 1 to 32 logs through the existing work routine.";
        }
        if (action.type() == AiAction.Type.MINE_RESOURCE && (action.item() == null
                || !Set.of("minecraft:coal", "minecraft:raw_iron", "minecraft:raw_gold", "minecraft:raw_copper").contains(action.item())
                || action.count() < 1 || action.count() > 32)) return "Mining supports 1 to 32 coal, raw iron, raw gold or raw copper with a suitable pickaxe.";
        return null;
    }
    private static boolean hasPickaxe(PlayerNpcEntity npc) {
        if (npc.getMainHandItem().getItem() instanceof PickaxeItem) return true;
        for (int i = 0; i < npc.getInventory().getContainerSize(); i++) if (npc.getInventory().getItem(i).getItem() instanceof PickaxeItem) return true;
        return false;
    }
}
