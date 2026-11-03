package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Shared threat selection and retreat mechanics; the calling goal owns MOVE and LOOK. */
public final class DangerousReactionAi {
    private static final int EXPLOSIVE_SCAN_TICKS = 10;
    private static final int HEALING_SCAN_TICKS = 20;
    private static final int REPATH_TICKS = 20;
    private static final int MAX_PATH_ATTEMPTS = 2;
    private static final double SCAN_RANGE = 16.0D;
    private static final double HOLD_MARGIN = 2.0D;
    private static final float PATH_NODE_MULTIPLIER = 0.15F;

    private final PlayerNpcEntity npc;
    private Entity explosiveThreat;
    private List<Entity> nearbyExplosives = List.of();
    private LivingEntity cautiousThreat;
    private Entity movementThreat;
    private Path retreatPath;
    private int nextExplosiveScanTick;
    private int nextHealingScanTick;
    private int nextPathTick;
    private int nextCandidateIndex;
    private boolean ownsMovement;

    public DangerousReactionAi(PlayerNpcEntity npc) {
        this.npc = npc;
        // Brief emergency scans are staggered independently of ordinary work admission.
        this.nextExplosiveScanTick = npc.tickCount + Math.floorMod(npc.getUUID().hashCode(), EXPLOSIVE_SCAN_TICKS);
        this.nextHealingScanTick = npc.tickCount;
    }

    @Nullable
    public Entity findThreat(boolean includeHealingThreat) {
        if (!this.canReact()) {
            return null;
        }
        if (!isExplosiveThreat(this.explosiveThreat) || !this.isWithinDangerRange(this.explosiveThreat, 4.0D)) {
            this.explosiveThreat = null;
        }
        if (this.npc.tickCount >= this.nextExplosiveScanTick) {
            this.nextExplosiveScanTick = this.npc.tickCount + EXPLOSIVE_SCAN_TICKS;
            this.explosiveThreat = null;
            double bestRelativeDistance = Double.MAX_VALUE;
            // Entity queries observe loaded entities and never request chunks.
            this.nearbyExplosives = this.npc.level().getEntities(this.npc, this.npc.getBoundingBox().inflate(SCAN_RANGE), DangerousReactionAi::isExplosiveThreat);
            for (Entity candidate : this.nearbyExplosives) {
                if (!this.isWithinDangerRange(candidate, 4.0D)) {
                    continue;
                }
                double radius = this.safeDistance(candidate);
                double relativeDistance = this.npc.distanceToSqr(candidate) / (radius * radius);
                if (relativeDistance < bestRelativeDistance) {
                    bestRelativeDistance = relativeDistance;
                    this.explosiveThreat = candidate;
                }
            }
        }
        if (this.explosiveThreat != null && this.isWithinDangerRange(this.explosiveThreat, HOLD_MARGIN)) {
            return this.explosiveThreat;
        }
        if (!includeHealingThreat || !this.npc.isHealing() && this.npc.getHealth() >= this.npc.getMaxHealth() * 0.70F) {
            return this.explosiveThreat;
        }
        LivingEntity target = this.npc.getTarget();
        LivingEntity healingThreat = this.isHealingThreat(target) ? target : null;
        LivingEntity attacker = this.npc.getLastHurtByMob();
        if (this.isHealingThreat(attacker) && (healingThreat == null || this.npc.distanceToSqr(attacker) < this.npc.distanceToSqr(healingThreat))) {
            healingThreat = attacker;
        }
        if (this.npc.hasInterest(PlayerNpcInterest.CAUTIOUS)) {
            if (this.npc.tickCount >= this.nextHealingScanTick) {
                this.nextHealingScanTick = this.npc.tickCount + HEALING_SCAN_TICKS;
                this.cautiousThreat = CautiousThreatAi.findNearestThreat(this.npc, SCAN_RANGE);
            }
            if (this.isHealingThreat(this.cautiousThreat) && (healingThreat == null || this.npc.distanceToSqr(this.cautiousThreat) < this.npc.distanceToSqr(healingThreat))) {
                healingThreat = this.cautiousThreat;
            }
        }
        if (healingThreat != null) {
            return healingThreat;
        }
        // A distant explosive must not make a healing NPC hold still beside an attacker.
        // Retain it as a fallback so idle wandering does not lead back into its radius.
        return this.explosiveThreat;
    }

    public static boolean isExplosiveThreat(@Nullable Entity entity) {
        if (entity == null || !entity.isAlive() || entity.isRemoved()) {
            return false;
        }
        return entity instanceof PrimedTnt
                || entity instanceof Creeper creeper && (creeper.isIgnited() || creeper.getSwellDir() > 0);
    }

    public boolean isWithinDangerRange(@Nullable Entity threat, double extraDistance) {
        double distance = this.safeDistance(threat) + extraDistance;
        return threat != null && threat.level() == this.npc.level() && this.npc.distanceToSqr(threat) < distance * distance;
    }

    /** A null threat releases only movement previously owned by this helper. */
    public void tickRetreat(@Nullable Entity threat, double speed, boolean sprint) {
        if (!this.canReact() || threat == null || !threat.isAlive() || threat.isRemoved()
                || threat.level() != this.npc.level()) {
            if (this.ownsMovement) {
                this.stop();
            }
            return;
        }
        if (this.movementThreat != threat) {
            this.movementThreat = threat;
            this.retreatPath = null;
            this.npc.getNavigation().stop();
        }
        this.ownsMovement = true;
        this.npc.getLookControl().setLookAt(threat, 60.0F, 60.0F);
        if (!this.npc.isHealing() && !this.isWithinDangerRange(threat, HOLD_MARGIN)) {
            this.npc.getNavigation().stop();
            this.retreatPath = null;
            this.npc.setSprinting(false);
            return;
        }
        this.npc.setSprinting(sprint);
        double currentDistanceSqr = this.npc.distanceToSqr(threat);
        if (this.npc.getNavigation().isDone() && this.retreatPath != null && !this.retreatPath.isDone()
                && this.retreatPath.getEndNode() != null) {
            Vec3 endpoint = Vec3.atBottomCenterOf(this.retreatPath.getEndNode().asBlockPos());
            if (endpoint.distanceToSqr(threat.position()) > currentDistanceSqr + 1.0D
                    && this.isSafeFromOtherExplosive(endpoint, threat)) {
                // Resume an interrupted route without another expensive search.
                this.npc.getNavigation().moveTo(this.retreatPath, Math.min(speed, sprint ? 0.79D : 1.0D));
            } else {
                this.retreatPath = null;
            }
        }
        if (this.npc.tickCount < this.nextPathTick) {
            return;
        }
        this.nextPathTick = this.npc.tickCount + REPATH_TICKS;
        List<Vec3> destinations = this.findRetreatCandidates(threat);
        int attempts = 0;
        int firstCandidate = destinations.isEmpty() ? 0 : Math.floorMod(this.nextCandidateIndex, destinations.size());
        for (int offset = 0; offset < destinations.size(); offset++) {
            int candidateIndex = (firstCandidate + offset) % destinations.size();
            Vec3 destination = destinations.get(candidateIndex);
            if (destination == null || destination.distanceToSqr(threat.position()) <= currentDistanceSqr + 1.0D
                    || !this.isSafeFromOtherExplosive(destination, threat)) {
                continue;
            }
            if (attempts++ >= MAX_PATH_ATTEMPTS) {
                break;
            }
            // Retry other directions after failures rather than starving the back
            // half of the fan behind the same two unreachable destinations.
            this.nextCandidateIndex = candidateIndex + 1;
            Path path = PathNavigationAi.createBoundedPath(this.npc, BlockPos.containing(destination), PATH_NODE_MULTIPLIER);
            if (path == null || path.getNodeCount() == 0 || path.getEndNode() == null) {
                continue;
            }
            Vec3 endpoint = Vec3.atBottomCenterOf(path.getEndNode().asBlockPos());
            if (endpoint.distanceToSqr(threat.position()) <= currentDistanceSqr + 1.0D
                    || !this.isSafeFromOtherExplosive(endpoint, threat)) {
                continue;
            }
            if (this.npc.getNavigation().moveTo(path, Math.min(speed, sprint ? 0.79D : 1.0D))) {
                this.retreatPath = path;
                this.nextCandidateIndex = 0;
                this.npc.setCurrentAiDetail("retreating from " + threat.getDisplayName().getString());
                return;
            }
        }
        // Never continue an old combat approach or stale escape into a moving hazard.
        this.npc.getNavigation().stop();
        this.retreatPath = null;
        this.npc.setSprinting(false);
        this.npc.setCurrentAiDetail("retreat blocked: no reachable escape path");
    }

    public void stop() {
        if (this.ownsMovement) {
            this.npc.getNavigation().stop();
            this.npc.setSprinting(false);
        }
        this.ownsMovement = false;
        this.movementThreat = null;
        this.retreatPath = null;
        // Preserve the path deadline across goal interruptions to bound retries.
    }

    private boolean canReact() {
        return this.npc.level() instanceof ServerLevel && this.npc.isAlive()
                && !this.npc.isNoAi() && !this.npc.isPassenger();
    }

    private List<Vec3> findRetreatCandidates(Entity threat) {
        List<Vec3> candidates = new ArrayList<>();
        if (!(this.npc.level() instanceof ServerLevel serverLevel)) {
            return candidates;
        }
        Vec3 away = this.npc.position().subtract(threat.position()).multiply(1.0D, 0.0D, 1.0D);
        if (away.lengthSqr() < 1.0E-4D) {
            away = Vec3.directionFromRotation(0.0F, this.npc.getYRot());
        }
        away = away.normalize();
        // Prefer close, grounded escape steps; random distant samples can all be
        // unreachable during a short meal even when the ground behind us is open.
        for (double angle : new double[]{0.0D, Math.PI / 4.0D, -Math.PI / 4.0D, Math.PI / 2.0D, -Math.PI / 2.0D}) {
            double dx = away.x * Math.cos(angle) - away.z * Math.sin(angle);
            double dz = away.x * Math.sin(angle) + away.z * Math.cos(angle);
            BlockPos horizontal = BlockPos.containing(this.npc.getX() + dx * 4.0D, this.npc.getY(), this.npc.getZ() + dz * 4.0D);
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos candidate = horizontal.offset(0, dy, 0);
                if (PathNavigationAi.canStandAt(serverLevel, candidate)) {
                    candidates.add(Vec3.atBottomCenterOf(candidate));
                    break;
                }
            }
        }
        Vec3 random = DefaultRandomPos.getPosAway(this.npc, 8, 4, threat.position());
        if (random != null) {
            candidates.add(random);
        }
        return candidates;
    }

    private boolean isSafeFromOtherExplosive(Vec3 position, Entity threat) {
        for (Entity explosive : this.nearbyExplosives) {
            if (explosive == threat || !isExplosiveThreat(explosive) || explosive.level() != this.npc.level()) {
                continue;
            }
            double radius = this.safeDistance(explosive) + HOLD_MARGIN;
            double minimumDistanceSqr = Math.min(this.npc.distanceToSqr(explosive), radius * radius);
            // If already surrounded, allow gradual escape without moving closer to
            // another explosive; otherwise keep the destination outside its margin.
            if (position.distanceToSqr(explosive.position()) < minimumDistanceSqr) {
                return false;
            }
        }
        return true;
    }

    private boolean isHealingThreat(@Nullable LivingEntity threat) {
        return threat != null && threat != this.npc && threat.isAlive() && !threat.isRemoved()
                && threat.level() == this.npc.level() && !threat.isSpectator()
                && !(threat instanceof Player player && player.isCreative())
                && !this.npc.isAlliedTo(threat) && !threat.isAlliedTo(this.npc)
                && this.npc.distanceToSqr(threat) < SCAN_RANGE * SCAN_RANGE;
    }

    private double safeDistance(@Nullable Entity threat) {
        if (threat instanceof Creeper creeper) {
            return creeper.isPowered() ? 10.0D : 6.0D;
        }
        return 8.0D;
    }
}
