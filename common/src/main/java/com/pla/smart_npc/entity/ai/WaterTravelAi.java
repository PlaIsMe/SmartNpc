package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared swim motion; the active goal/navigation still owns the destination and route lifetime. */
public final class WaterTravelAi {
    private static final int INTENT_GRACE_TICKS = 6;
    private static final int SURFACE_SEARCH_HEIGHT = 6;
    private static final double SURFACE_ACCELERATION = 0.018D;
    private static final double SURFACE_SPEED_CAP = 0.10D;
    private static final double UNDERWATER_ACCELERATION = 0.025D;
    private static final double UNDERWATER_SPEED_CAP = 0.16D;
    private final PlayerNpcEntity npc;
    private Object owner;
    private BlockPos destination;
    private int intentUntilTick;
    private double speed;
    private boolean propel;
    private boolean diving;
    private boolean recoveringAir;
    private int diveUntilTick;
    private int nextDiveTick;
    private int nextWaterCheckTick;
    private double diveY;
    private boolean clearSurface;
    private boolean swimming;

    public WaterTravelAi(PlayerNpcEntity npc) {
        this.npc = npc;
    }

    public void request(Object owner, BlockPos destination, double speed, boolean propel) {
        if (this.owner != owner) {
            this.diving = false;
            this.nextDiveTick = this.npc.tickCount + 10 + this.npc.getRandom().nextInt(16);
        }
        this.owner = owner;
        this.destination = destination.immutable();
        this.speed = Math.max(0.1D, Math.min(1.0D, speed));
        this.propel = propel;
        this.intentUntilTick = this.npc.tickCount + INTENT_GRACE_TICKS;
    }

    public void release(Object owner) {
        if (this.owner == owner) {
            this.owner = null;
            this.destination = null;
            this.resetMotion();
        }
    }

    /** FloatGoal may still supply emergency buoyancy; it must not fight an intentional dive. */
    public boolean suppressFloating() {
        return this.diving && this.npc.getAirSupply() > 100 && !this.isInBubbleColumn()
                && !this.npc.isInLava() && this.npc.tickCount <= this.intentUntilTick;
    }

    public boolean isSwimming() {
        return this.swimming;
    }

    public double swimAcceleration() {
        return this.npc.isUnderWater() ? UNDERWATER_ACCELERATION : SURFACE_ACCELERATION;
    }

    public double swimSpeedCap() {
        return this.npc.isUnderWater() ? UNDERWATER_SPEED_CAP : SURFACE_SPEED_CAP;
    }

    public String phase() {
        return this.diving ? "diving" : this.npc.isUnderWater() ? "surfacing" : "surface swimming";
    }

    public void tick(ServerLevel level) {
        if (!this.npc.isAlive() || this.npc.isNoAi() || this.npc.isPassenger() || this.npc.isSleeping()
                || !this.npc.isInWater() || this.npc.isInLava() || this.isInBubbleColumn()) {
            this.resetMotion();
            return;
        }
        boolean explicitIntent = this.destination != null && this.npc.tickCount <= this.intentUntilTick;
        if (!explicitIntent && this.destination != null) {
            this.owner = null;
            this.destination = null;
            this.diving = false;
        }
        Path path = this.npc.getNavigation().getPath();
        BlockPos target = explicitIntent ? this.destination
                : path != null && !path.isDone() ? this.npc.getNavigation().getTargetPos() : null;
        if (target == null || this.npc.isUsingItem()) {
            this.resetMotion();
            return;
        }
        // Navigation is already a live owning route, not a speculative new target/path search.
        if (!explicitIntent) {
            this.intentUntilTick = this.npc.tickCount + 1;
        }
        double dx = target.getX() + 0.5D - this.npc.getX();
        double dz = target.getZ() + 0.5D - this.npc.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        // Follow the existing path around obstacles; only its owner may request direct crossing.
        Vec3 steeringTarget = !explicitIntent && path != null && !path.isDone()
                ? path.getNextEntityPos(this.npc) : Vec3.atCenterOf(target);
        double steerX = steeringTarget.x - this.npc.getX();
        double steerZ = steeringTarget.z - this.npc.getZ();
        double steeringDistance = Math.sqrt(steerX * steerX + steerZ * steerZ);
        if (this.npc.getAirSupply() < 140) {
            this.recoveringAir = true;
        } else if (!this.npc.isUnderWater() && this.npc.getAirSupply() >= this.npc.getMaxAirSupply() - 10) {
            this.recoveringAir = false;
        }
        if (this.nextDiveTick == 0) {
            this.nextDiveTick = this.npc.tickCount + 10 + this.npc.getRandom().nextInt(16);
        }
        if (this.npc.tickCount >= this.nextWaterCheckTick) {
            this.nextWaterCheckTick = this.npc.tickCount + 20;
            // At most two local six-block columns, no pathfinding, area scan or chunk loading.
            this.clearSurface = this.hasOpenSurface(level, this.npc.blockPosition());
            if (!this.diving && !this.recoveringAir && !this.npc.isUnderWater() && distance > 4.0D
                    && this.npc.tickCount >= this.nextDiveTick && this.clearSurface) {
                this.nextDiveTick = this.npc.tickCount + 20 + this.npc.getRandom().nextInt(21);
                double depth = 1.4D + this.npc.getRandom().nextDouble() * 0.6D;
                AABB submerged = this.standingBox().move(0.0D, -depth, 0.0D);
                BlockPos ahead = BlockPos.containing(this.npc.getX() + dx / distance,
                        this.npc.getY() - depth, this.npc.getZ() + dz / distance);
                if (this.waterBodyFits(level, submerged) && this.hasOpenSurface(level, ahead)
                        && this.waterBodyFits(level, submerged.move(dx / distance, 0.0D, dz / distance))) {
                    this.diving = true;
                    this.diveY = this.npc.getY() - depth;
                    this.diveUntilTick = this.npc.tickCount + 100 + this.npc.getRandom().nextInt(41);
                }
            }
        }
        Vec3 motion = this.npc.getDeltaMovement();
        // A tiny swept local collision/fluid check is cheap and prevents swimming into banks,
        // seafloors, ice or an unloaded edge between the slower dive decisions.
        if (this.diving && (this.npc.tickCount >= this.diveUntilTick || this.recoveringAir
                || distance <= 3.0D || this.npc.horizontalCollision || !this.clearSurface
                || !this.canDescend(level))) {
            this.diving = false;
            this.nextDiveTick = this.npc.tickCount + 20 + this.npc.getRandom().nextInt(21);
        }
        double vertical = this.diving
                ? Math.max(-0.12D, Math.min(0.08D, (this.diveY - this.npc.getY()) * 0.12D))
                : this.npc.isUnderWater() ? Math.max(motion.y, 0.12D) : motion.y;
        double nextX = motion.x;
        double nextZ = motion.z;
        if ((!explicitIntent || this.propel) && distance > 1.0D && steeringDistance > 0.05D) {
            double routeSpeed = explicitIntent ? this.speed : 1.0D;
            AABB ahead = this.standingBox().expandTowards(steerX / steeringDistance * 0.35D, 0.0D, steerZ / steeringDistance * 0.35D);
            if (this.loadedBox(level, ahead) && level.noCollision(this.npc, ahead)) {
                nextX += steerX / steeringDistance * this.swimAcceleration() * routeSpeed;
                nextZ += steerZ / steeringDistance * this.swimAcceleration() * routeSpeed;
            }
        }
        // Owner-supplied steering and live navigation share the slower surface pace too.
        double horizontal = Math.sqrt(nextX * nextX + nextZ * nextZ);
        double cap = this.swimSpeedCap() * (explicitIntent ? this.speed : 1.0D);
        if (horizontal > cap) {
            nextX *= cap / horizontal;
            nextZ *= cap / horizontal;
        }
        if (this.npc.isUnderWater()) {
            // Do not carry even the owner's existing impulse under ice/overhangs. This is a
            // fixed local safety column, not destination discovery or a path search.
            BlockPos ahead = BlockPos.containing(this.npc.getX() + nextX * 3.0D, this.npc.getY(), this.npc.getZ() + nextZ * 3.0D);
            if (!this.hasOpenSurface(level, ahead)) {
                this.diving = false;
                nextX = 0.0D;
                nextZ = 0.0D;
                vertical = Math.max(vertical, 0.12D);
            }
        }
        this.npc.setDeltaMovement(nextX, vertical, nextZ);
        this.setSwimmingPose(this.npc.isUnderWater() && distance > 2.0D);
        if (this.diving) {
            this.npc.setJumping(false);
        }
    }

    private void resetMotion() {
        this.diving = false;
        this.setSwimmingPose(false);
        this.nextDiveTick = 0;
        if (!this.npc.isInWater()) {
            this.recoveringAir = false;
        }
    }

    private AABB standingBox() {
        return this.npc.getDimensions(Pose.STANDING).makeBoundingBox(this.npc.position());
    }

    private boolean isInBubbleColumn() {
        BlockPos feet = this.npc.blockPosition();
        return this.npc.level().hasChunkAt(feet) && this.npc.level().getBlockState(feet).is(Blocks.BUBBLE_COLUMN);
    }

    private boolean canDescend(ServerLevel level) {
        AABB below = this.standingBox().move(0.0D, -0.25D, 0.0D);
        // Headroom may still be air while entering the water from a surface float.
        // Requiring the whole current body to be submerged would cancel every such dive.
        BlockPos feet = BlockPos.containing(this.npc.getX(), below.minY, this.npc.getZ());
        return this.loadedBox(level, below) && level.noCollision(this.npc, below)
                && level.getFluidState(feet).is(FluidTags.WATER)
                && !level.getBlockState(feet).is(Blocks.BUBBLE_COLUMN);
    }

    private void setSwimmingPose(boolean swimming) {
        this.swimming = swimming;
        this.npc.setSwimming(swimming);
        if (swimming && this.npc.getPose() == Pose.STANDING) {
            this.npc.setPose(Pose.SWIMMING);
        } else if (!swimming && this.npc.getPose() == Pose.SWIMMING
                && this.npc.level() instanceof ServerLevel level
                && this.loadedBox(level, this.standingBox()) && level.noCollision(this.npc, this.standingBox())) {
            this.npc.setPose(Pose.STANDING);
        }
    }

    private boolean hasOpenSurface(ServerLevel level, BlockPos from) {
        for (int y = 0; y <= SURFACE_SEARCH_HEIGHT; y++) {
            BlockPos pos = from.above(y);
            if (!level.hasChunkAt(pos) || level.getBlockState(pos).is(Blocks.BUBBLE_COLUMN)
                    || !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
            if (level.getFluidState(pos).isEmpty()) {
                BlockPos above = pos.above();
                return level.hasChunkAt(above) && level.getFluidState(above).isEmpty()
                        && level.getBlockState(above).getCollisionShape(level, above).isEmpty();
            }
            if (!level.getFluidState(pos).is(FluidTags.WATER)) {
                return false;
            }
        }
        return false;
    }

    private boolean waterBodyFits(ServerLevel level, AABB box) {
        if (!this.loadedBox(level, box) || !level.noCollision(this.npc, box)) {
            return false;
        }
        BlockPos feet = BlockPos.containing((box.minX + box.maxX) * 0.5D, box.minY, (box.minZ + box.maxZ) * 0.5D);
        BlockPos head = BlockPos.containing((box.minX + box.maxX) * 0.5D, box.maxY - 0.05D, (box.minZ + box.maxZ) * 0.5D);
        return level.getFluidState(feet).is(FluidTags.WATER) && level.getFluidState(head).is(FluidTags.WATER)
                && !level.getBlockState(feet).is(Blocks.BUBBLE_COLUMN) && !level.getBlockState(head).is(Blocks.BUBBLE_COLUMN);
    }

    private boolean loadedBox(ServerLevel level, AABB box) {
        return level.hasChunkAt(BlockPos.containing(box.minX, box.minY, box.minZ))
                && level.hasChunkAt(BlockPos.containing(box.minX, box.minY, box.maxZ))
                && level.hasChunkAt(BlockPos.containing(box.maxX, box.minY, box.minZ))
                && level.hasChunkAt(BlockPos.containing(box.maxX, box.minY, box.maxZ));
    }
}
