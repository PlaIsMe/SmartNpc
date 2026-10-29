package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcAlertManager;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class RespondToNpcAlertGoal extends Goal {
    private static final double ALERT_RADIUS = 36.0D;
    private static final double AVOID_SPEED = 1.0D;
    private static final int AVOID_TICKS = 80;
    private static final int FAILED_ROUTE_BACKOFF_TICKS = 100;
    private static final int COMPLETED_ROUTE_BACKOFF_TICKS = 40;
    private static final int DECISION_TICKS = 200;
    private static final int MAX_REMEMBERED_THREATS = 4;
    private static final int[] STAND_HEIGHT_OFFSETS = {0, 1, -1, -2};
    private static final float PATH_NODE_MULTIPLIER = 0.03F;
    private static final float OVERLOADED_PATH_NODE_MULTIPLIER = 0.01F;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final Map<UUID, ThreatResponse> responses = new LinkedHashMap<>();
    private LivingEntity threat;
    private boolean avoiding;
    private boolean attackResponse;
    private Path selectedPath;
    private long avoidUntil;
    private Vec3 avoidanceStart;
    private ThreatResponse response;

    public RespondToNpcAlertGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.threat = PlayerNpcAlertManager.getNearbyThreat(this.playerNpc, ALERT_RADIUS).orElse(null);
        this.selectedPath = null;
        if (this.threat == null) {
            return false;
        }
        long now = serverLevel.getGameTime();
        this.responses.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        this.response = this.responses.get(this.threat.getUUID());
        if (this.response == null) {
            if (this.responses.size() >= MAX_REMEMBERED_THREATS) {
                this.responses.remove(this.responses.keySet().iterator().next());
            }
            this.response = new ThreatResponse(this.shouldAttack(this.threat), this.playerNpc.getRandom().nextFloat(), now + DECISION_TICKS);
            this.responses.put(this.threat.getUUID(), this.response);
        }
        if (now < this.response.retryAt) {
            return false;
        }
        // Reconsider immediate safety without rerolling the random attack decision on every
        // failed route or selector restart for this same alert threat.
        this.attackResponse = this.response.attack && !this.playerNpc.hasInterest(PlayerNpcInterest.CAUTIOUS)
                && !this.playerNpc.shouldSmartNpcFleeFromTarget(this.threat)
                && (!this.playerNpc.isSmartNpcCompatHighDangerThreat(this.threat)
                || this.response.compatibilityRoll <= this.playerNpc.getSmartNpcTargetAttackChance(this.threat, 1.0F))
                && this.playerNpc.getHealth() / this.playerNpc.getMaxHealth() > 0.55F;
        if (this.attackResponse) {
            return true;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)) {
            return false;
        }
        this.selectedPath = this.findUsefulAwayPath(serverLevel);
        if (this.selectedPath == null) {
            this.deferResponse(FAILED_ROUTE_BACKOFF_TICKS);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.avoiding
                && this.playerNpc.level().getGameTime() < this.avoidUntil
                && this.playerNpc.isAlive() && !this.playerNpc.isNoAi() && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing() && this.playerNpc.getTarget() == null
                && this.threat != null
                && this.threat.isAlive()
                && this.threat.level() == this.playerNpc.level()
                && !this.playerNpc.getNavigation().isDone() && !this.playerNpc.getNavigation().isStuck()
                && !this.playerNpc.isTeamAlliedWith(this.threat);
    }

    @Override
    public void start() {
        if (this.threat == null || this.playerNpc.isTeamAlliedWith(this.threat)) {
            return;
        }

        if (this.attackResponse) {
            this.playerNpc.setSprinting(false);
            this.playerNpc.setTarget(this.threat);
            this.playerNpc.setCurrentAiState("ai.player_npc.assisting_alert");
            this.avoiding = false;
            this.deferResponse(this.playerNpc.getTarget() == this.threat
                    ? COMPLETED_ROUTE_BACKOFF_TICKS : FAILED_ROUTE_BACKOFF_TICKS);
            return;
        }

        if (!this.isUsefulAwayPath(this.selectedPath)
                || !this.playerNpc.getNavigation().moveTo(this.selectedPath, AVOID_SPEED)) {
            this.avoiding = false;
            this.deferResponse(FAILED_ROUTE_BACKOFF_TICKS);
            return;
        }
        this.avoiding = true;
        this.avoidUntil = this.playerNpc.level().getGameTime() + AVOID_TICKS;
        this.avoidanceStart = this.playerNpc.position();
        this.playerNpc.setSprinting(true);
        this.playerNpc.setTarget(null);
        this.playerNpc.setCurrentAiState("ai.player_npc.avoiding_alert");
    }

    @Override
    public void tick() {
        if (!this.avoiding || this.threat == null) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.threat, 40.0F, 40.0F);
    }

    @Override
    public void stop() {
        if (this.avoiding) {
            boolean moved = this.avoidanceStart != null
                    && this.playerNpc.position().subtract(this.avoidanceStart).horizontalDistanceSqr() >= 4.0D;
            this.deferResponse(moved ? COMPLETED_ROUTE_BACKOFF_TICKS : FAILED_ROUTE_BACKOFF_TICKS);
            this.playerNpc.getNavigation().stop();
        }
        this.playerNpc.setSprinting(false);
        this.threat = null;
        this.avoiding = false;
        this.avoidUntil = 0L;
        this.selectedPath = null;
        this.avoidanceStart = null;
        this.response = null;
        String state = this.playerNpc.getCurrentAiState();
        if ("ai.player_npc.avoiding_alert".equals(state) || "ai.player_npc.assisting_alert".equals(state)) {
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        }
    }

    private boolean shouldAttack(LivingEntity threat) {
        if (this.playerNpc.hasInterest(PlayerNpcInterest.CAUTIOUS) || this.playerNpc.shouldSmartNpcFleeFromTarget(threat)) {
            return false;
        }
        float healthRatio = this.playerNpc.getHealth() / this.playerNpc.getMaxHealth();
        double myPower = this.powerScore(this.playerNpc);
        double threatPower = this.powerScore(threat);
        return healthRatio > 0.55F && (myPower + 4.0D >= threatPower || this.playerNpc.getRandom().nextFloat() < 0.35F);
    }

    private Path findUsefulAwayPath(ServerLevel serverLevel) {
        if (this.threat == null) {
            return null;
        }

        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            Vec3 away = this.playerNpc.position().subtract(this.threat.position()).multiply(1.0D, 0.0D, 1.0D);
            if (away.lengthSqr() < 1.0E-4D) {
                away = Vec3.directionFromRotation(0.0F, this.playerNpc.getYRot());
            }
            away = away.normalize();
            BlockPos destination = null;
            // Twelve cheap loaded-stand checks and at most one path per admitted attempt.
            // Near local destinations let the deliberately small node budget finish a route.
            for (int attempt = 0; attempt < 3 && destination == null; attempt++) {
                Vec3 direction = away.yRot((this.playerNpc.getRandom().nextFloat() - 0.5F) * 1.2F);
                BlockPos horizontal = BlockPos.containing(this.playerNpc.position().add(direction.scale(4 + attempt * 2)));
                for (int dy : STAND_HEIGHT_OFFSETS) {
                    BlockPos candidate = horizontal.offset(0, dy, 0);
                    if (serverLevel.hasChunkAt(candidate) && serverLevel.hasChunkAt(candidate.below())
                            && serverLevel.hasChunkAt(candidate.above())
                            && serverLevel.isInWorldBounds(candidate) && serverLevel.getWorldBorder().isWithinBounds(candidate)
                            && PathNavigationAi.canStandAt(serverLevel, candidate)
                            && serverLevel.getFluidState(candidate).isEmpty()) {
                        destination = candidate;
                        break;
                    }
                }
            }
            if (destination == null) {
                return null;
            }
            float pathBudget = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                    ? OVERLOADED_PATH_NODE_MULTIPLIER
                    : PATH_NODE_MULTIPLIER;
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    destination,
                    pathBudget
            );
            return this.isUsefulAwayPath(path) ? path : null;
        } finally {
            // This goal is deliberately not a routine-worker wrapper, so time its only expensive
            // operation directly rather than leaving future alert-route regressions hidden in
            // the residual Mob.super.tick() bucket.
            PlayerNpcPerformanceMonitor.recordGoalWork(
                    this.playerNpc,
                    this.getClass().getSimpleName() + ".findUsefulAwayPath",
                    timing
            );
        }
    }

    private boolean isUsefulAwayPath(Path path) {
        if (path == null || !path.canReach() || path.getEndNode() == null || this.threat == null) {
            return false;
        }
        Vec3 endpoint = Vec3.atBottomCenterOf(path.getEndNode().asBlockPos());
        double currentThreatDistance = Math.sqrt(this.playerNpc.position().subtract(this.threat.position()).horizontalDistanceSqr());
        return endpoint.subtract(this.playerNpc.position()).horizontalDistanceSqr() >= 4.0D
                && endpoint.subtract(this.threat.position()).horizontalDistanceSqr()
                >= (currentThreatDistance + 1.0D) * (currentThreatDistance + 1.0D);
    }

    private void deferResponse(int ticks) {
        if (this.response != null) {
            long retryAt = this.playerNpc.level().getGameTime() + ticks + this.playerNpc.getRandom().nextInt(21);
            this.response.retryAt = Math.max(this.response.retryAt, retryAt);
            this.response.expiresAt = Math.max(this.response.expiresAt, retryAt);
        }
    }

    private static final class ThreatResponse {
        private final boolean attack;
        private final float compatibilityRoll;
        private long expiresAt;
        private long retryAt;

        private ThreatResponse(boolean attack, float compatibilityRoll, long expiresAt) {
            this.attack = attack;
            this.compatibilityRoll = compatibilityRoll;
            this.expiresAt = expiresAt;
        }
    }

    private double powerScore(LivingEntity entity) {
        double score = entity.getHealth() * 0.4D + entity.getArmorValue();
        score += itemPower(entity.getMainHandItem()) * 1.4D;
        score += itemPower(entity.getOffhandItem()) * 0.5D;
        for (ItemStack stack : entity.getArmorSlots()) {
            score += itemPower(stack);
        }
        return score;
    }

    private double itemPower(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double score = 0.0D;
        if (stack.getItem() instanceof SwordItem swordItem) {
            score += swordItem.getDamage(stack);
        } else if (stack.getItem() instanceof AxeItem axeItem) {
            score += axeItem.getDamage(stack);
        }
        if (stack.getItem() instanceof ArmorItem armorItem) {
            score += armorItem.getDefense() + armorItem.getToughness();
        }
        if (stack.isEnchanted()) {
            score += 2.0D;
        }
        return score;
    }
}
