package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.ai.AiChatService;
import com.pla.smart_npc.ai.AiWorldInteractions;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.EnumSet;

/** Brief look at a confirmed entity, or bounded sideways evasion of a drawn bow. */
public final class AiObserveEntityGoal extends Goal {
    private final PlayerNpcEntity npc;
    private AiWorldInteractions.Observation order;
    private Entity target;
    private int nextPath;
    private boolean moved;
    public AiObserveEntityGoal(PlayerNpcEntity npc) { this.npc = npc; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    @Override public boolean canUse() {
        AiChatService service = AiChatService.get(npc.getServer());
        order = service == null || !service.ready() ? null : service.interactions().observation(npc);
        target = order == null ? null : ((ServerLevel) npc.level()).getEntity(order.target());
        return valid();
    }
    private boolean valid() {
        AiChatService service = AiChatService.get(npc.getServer());
        return service != null && service.ready() && order != null && order == service.interactions().observation(npc)
                && npc.isAlive() && !npc.isNoAi() && !npc.isHealing() && !npc.isSleeping() && npc.getTarget() == null
                && !npc.isOnFire() && !npc.isInWater() && !npc.isInLava() && !npc.isInWall() && npc.getUpwardEscapeTarget() == null
                && target != null && target.isAlive() && !target.isRemoved() && target.level() == npc.level() && npc.distanceToSqr(target) <= 256;
    }
    @Override public boolean canContinueToUse() { return valid(); }
    @Override public void start() { nextPath = 0; moved = false; }
    @Override public void tick() {
        if (!valid()) return;
        npc.getLookControl().setLookAt(target, 30, 30);
        if (!order.evade() || npc.tickCount < nextPath || !PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(npc)) return;
        nextPath = npc.tickCount + 40;
        var away = npc.position().subtract(target.position()).normalize();
        int side = npc.getRandom().nextBoolean() ? 1 : -1;
        BlockPos stand = BlockPos.containing(npc.position().add(away.z * 3 * side + away.x * 2, 0, -away.x * 3 * side + away.z * 2));
        if (!npc.level().hasChunkAt(stand)) return;
        var path = PathNavigationAi.createBoundedPath(npc, stand, 0.05F);
        if (path != null && path.canReach()) { npc.getNavigation().moveTo(path, 1.0D); moved = true; }
    }
    @Override public void stop() { if (moved) npc.getNavigation().stop(); order = null; target = null; }
}
