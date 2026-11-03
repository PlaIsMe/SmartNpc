package com.pla.smart_npc.entity;

import com.pla.smart_npc.init.SmartNpcModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

public class PlayerNpcFishingBobberEntity extends Projectile implements IEntityWithComplexSpawn {
    private static final EntityDataAccessor<Integer> DATA_HOOKED_ENTITY = SynchedEntityData.defineId(PlayerNpcFishingBobberEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_BITING = SynchedEntityData.defineId(PlayerNpcFishingBobberEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int MAX_OUT_OF_WATER_TICKS = 10;
    private static final int KEEP_ALIVE_TICKS = 100;
    private static final int XP_PER_CATCH = 2;
    private static final double CAST_SPEED_MIN = 0.48D;
    private static final double CAST_SPEED_MAX = 1.15D;
    private static final double CAST_ARC_MIN = 0.11D;
    private static final double CAST_ARC_MAX = 0.22D;
    private static final double CAST_TARGET_JITTER = 0.28D;
    private static final int LURE_TIME_MIN_TICKS = 20 * 5;
    private static final int LURE_TIME_MAX_TICKS = 20 * 30;
    private static final int HOOK_TIME_MIN_TICKS = 20;
    private static final int HOOK_TIME_MAX_TICKS = 20 * 4;

    private final RandomSource synchronizedRandom = RandomSource.create();
    @Nullable
    private PlayerNpcEntity angler;
    @Nullable
    private Entity hookedIn;
    private int anglerId = -1;
    private int groundTicks;
    private int outOfWaterTicks;
    private int nibbleTicks;
    private int timeUntilLured;
    private int timeUntilHooked;
    private int keepAliveTicks = KEEP_ALIVE_TICKS;
    private int luck;
    private int lureSpeed;
    private float fishAngle;
    private boolean openWater = true;
    private State currentState = State.FLYING;

    public PlayerNpcFishingBobberEntity(EntityType<? extends PlayerNpcFishingBobberEntity> entityType, Level level) {
        super(entityType, level);
    }

    public void castFrom(PlayerNpcEntity angler, BlockPos waterPos, int luck, int lureSpeed) {
        this.angler = angler;
        this.anglerId = angler.getId();
        this.setOwner(angler);
        this.luck = Math.max(0, luck);
        this.lureSpeed = Math.max(0, lureSpeed);

        Vec3 start = new Vec3(angler.getX(), angler.getEyeY() - 0.1D, angler.getZ());
        double jitterX = Mth.nextDouble(angler.getRandom(), -CAST_TARGET_JITTER, CAST_TARGET_JITTER);
        double jitterZ = Mth.nextDouble(angler.getRandom(), -CAST_TARGET_JITTER, CAST_TARGET_JITTER);
        Vec3 target = Vec3.atCenterOf(waterPos).add(jitterX, 0.15D, jitterZ);
        Vec3 toTarget = target.subtract(start);
        double horizontalDistance = Math.max(0.001D, Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z));
        float yaw = (float) (Mth.atan2(toTarget.z, toTarget.x) * (180F / (float) Math.PI)) - 90.0F;
        float pitch = (float) -(Mth.atan2(toTarget.y, horizontalDistance) * (180F / (float) Math.PI));

        angler.setYRot(yaw);
        angler.setYHeadRot(yaw);
        angler.yBodyRot = yaw;
        angler.setXRot(pitch);
        this.snapTo(start.x, start.y, start.z, yaw, pitch);

        double arc = Mth.nextDouble(angler.getRandom(), CAST_ARC_MIN, CAST_ARC_MAX);
        Vec3 castVector = new Vec3(toTarget.x, toTarget.y + horizontalDistance * arc, toTarget.z);
        if (castVector.lengthSqr() < 1.0E-4D) {
            castVector = angler.getViewVector(1.0F);
        }
        double speed = Mth.clamp(horizontalDistance * 0.13D + angler.getRandom().nextDouble() * 0.12D, CAST_SPEED_MIN, CAST_SPEED_MAX);
        Vec3 motion = castVector.normalize().scale(speed);
        this.setDeltaMovement(motion);
        this.setYRot((float) (Mth.atan2(motion.x, motion.z) * (180F / (float) Math.PI)));
        this.setXRot((float) (Mth.atan2(motion.y, motion.horizontalDistance()) * (180F / (float) Math.PI)));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_HOOKED_ENTITY, 0);
        builder.define(DATA_BITING, false);
    }

    @Override
    public void onSyncedDataUpdated(@NotNull EntityDataAccessor<?> key) {
        if (DATA_HOOKED_ENTITY.equals(key)) {
            int entityId = this.getEntityData().get(DATA_HOOKED_ENTITY);
            this.hookedIn = entityId > 0 ? this.level().getEntity(entityId - 1) : null;
        }

        if (DATA_BITING.equals(key) && this.getEntityData().get(DATA_BITING)) {
            this.setDeltaMovement(
                    this.getDeltaMovement().x,
                    -0.4F * Mth.nextFloat(this.synchronizedRandom, 0.6F, 1.0F),
                    this.getDeltaMovement().z
            );
        }

        super.onSyncedDataUpdated(key);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 4096.0D;
    }

    @Override
    public void tick() {
        this.synchronizedRandom.setSeed(this.getUUID().getLeastSignificantBits() ^ this.level().getGameTime());
        super.tick();

        PlayerNpcEntity currentAngler = this.getAngler();
        if (currentAngler == null) {
            this.discard();
            return;
        }
        if (!this.level().isClientSide() && --this.keepAliveTicks <= 0) {
            this.discard();
            return;
        }
        if (!this.level().isClientSide() && this.shouldStopFishing(currentAngler)) {
            return;
        }

        float waterHeight = 0.0F;
        BlockPos blockPos = this.blockPosition();
        FluidState fluidState = this.level().getFluidState(blockPos);
        if (fluidState.is(FluidTags.WATER)) {
            waterHeight = fluidState.getHeight(this.level(), blockPos);
        }
        boolean inWater = waterHeight > 0.0F;

        if (this.currentState == State.FLYING) {
            if (this.hookedIn != null) {
                this.setDeltaMovement(Vec3.ZERO);
                this.currentState = State.HOOKED_IN_ENTITY;
                return;
            }

            if (inWater) {
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.3D, 0.2D, 0.3D));
                this.currentState = State.BOBBING;
                return;
            }

            this.checkCollision();
        } else if (this.currentState == State.HOOKED_IN_ENTITY) {
            if (this.hookedIn == null || this.hookedIn.isRemoved() || this.hookedIn.level().dimension() != this.level().dimension()) {
                this.setHookedEntity(null);
                this.currentState = State.FLYING;
            } else {
                this.setPos(this.hookedIn.getX(), this.hookedIn.getY(0.8D), this.hookedIn.getZ());
            }
            return;
        } else {
            Vec3 motion = this.getDeltaMovement();
            double yDrift = this.getY() + motion.y - blockPos.getY() - waterHeight;
            if (Math.abs(yDrift) < 0.01D) {
                yDrift += Math.signum(yDrift) * 0.1D;
            }
            this.setDeltaMovement(motion.x * 0.9D, motion.y - yDrift * this.random.nextFloat() * 0.2D, motion.z * 0.9D);

            if (this.nibbleTicks <= 0 && this.timeUntilHooked <= 0) {
                this.openWater = true;
            } else {
                this.openWater = this.openWater && this.outOfWaterTicks < MAX_OUT_OF_WATER_TICKS && this.calculateOpenWater(blockPos);
            }

            if (inWater) {
                this.outOfWaterTicks = Math.max(0, this.outOfWaterTicks - 1);
                if (this.getEntityData().get(DATA_BITING)) {
                    this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.1D * this.synchronizedRandom.nextFloat() * this.synchronizedRandom.nextFloat(), 0.0D));
                }
                if (!this.level().isClientSide()) {
                    this.tickFishBite(blockPos);
                }
            } else {
                this.outOfWaterTicks = Math.min(MAX_OUT_OF_WATER_TICKS, this.outOfWaterTicks + 1);
            }
        }

        if (!fluidState.is(FluidTags.WATER)) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.03D, 0.0D));
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
        this.updateRotation();
        if (this.currentState == State.FLYING && (this.onGround() || this.horizontalCollision)) {
            this.setDeltaMovement(Vec3.ZERO);
        }
        if (this.onGround()) {
            this.groundTicks++;
        } else {
            this.groundTicks = 0;
        }
        this.setDeltaMovement(this.getDeltaMovement().scale(0.92D));
        this.reapplyPosition();
    }

    private boolean shouldStopFishing(PlayerNpcEntity currentAngler) {
        boolean hasRod = currentAngler.getMainHandItem().canPerformAction(ItemAbilities.FISHING_ROD_CAST)
                || currentAngler.getOffhandItem().canPerformAction(ItemAbilities.FISHING_ROD_CAST);
        if (!currentAngler.isRemoved()
                && currentAngler.isAlive()
                && hasRod
                && this.distanceToSqr(currentAngler) <= 1024.0D) {
            return false;
        }

        this.discard();
        return true;
    }

    private void checkCollision() {
        HitResult hitResult = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hitResult.getType() == HitResult.Type.MISS || !net.neoforged.neoforge.event.EventHooks.onProjectileImpact(this, hitResult)) {
            this.onHit(hitResult);
        }
    }

    @Override
    protected boolean canHitEntity(@NotNull Entity target) {
        return super.canHitEntity(target) || target.isAlive() && target instanceof ItemEntity;
    }

    @Override
    protected void onHitEntity(@NotNull EntityHitResult result) {
        super.onHitEntity(result);
        if (!this.level().isClientSide()) {
            this.setHookedEntity(result.getEntity());
        }
    }

    @Override
    protected void onHitBlock(@NotNull BlockHitResult result) {
        super.onHitBlock(result);
        this.setDeltaMovement(this.getDeltaMovement().normalize().scale(result.distanceTo(this)));
    }

    private void setHookedEntity(@Nullable Entity hookedEntity) {
        this.hookedIn = hookedEntity;
        this.getEntityData().set(DATA_HOOKED_ENTITY, hookedEntity == null ? 0 : hookedEntity.getId() + 1);
    }

    private void tickFishBite(BlockPos pos) {
        ServerLevel serverLevel = (ServerLevel) this.level();
        int biteSpeed = 1;
        BlockPos aboveWater = pos.above();
        if (this.random.nextFloat() < 0.25F && this.level().isRainingAt(aboveWater)) {
            biteSpeed++;
        }
        if (this.random.nextFloat() < 0.5F && !this.level().canSeeSky(aboveWater)) {
            biteSpeed--;
        }

        if (this.nibbleTicks > 0) {
            this.nibbleTicks--;
            if (this.nibbleTicks <= 0) {
                this.timeUntilLured = 0;
                this.timeUntilHooked = 0;
                this.getEntityData().set(DATA_BITING, false);
            }
            return;
        }

        if (this.timeUntilHooked > 0) {
            this.timeUntilHooked -= biteSpeed;
            if (this.timeUntilHooked > 0) {
                this.fishAngle += (float) this.random.triangle(0.0D, 9.188D);
                float angle = this.fishAngle * ((float) Math.PI / 180F);
                float sin = Mth.sin(angle);
                float cos = Mth.cos(angle);
                double fishX = this.getX() + sin * this.timeUntilHooked * 0.1F;
                double fishY = Mth.floor(this.getY()) + 1.0F;
                double fishZ = this.getZ() + cos * this.timeUntilHooked * 0.1F;
                BlockState fishWater = serverLevel.getBlockState(BlockPos.containing(fishX, fishY - 1.0D, fishZ));
                if (fishWater.is(Blocks.WATER)) {
                    if (this.random.nextFloat() < 0.15F) {
                        serverLevel.sendParticles(ParticleTypes.BUBBLE, fishX, fishY - 0.1D, fishZ, 1, sin, 0.1D, cos, 0.0D);
                    }

                    float sideX = sin * 0.04F;
                    float sideZ = cos * 0.04F;
                    serverLevel.sendParticles(ParticleTypes.FISHING, fishX, fishY, fishZ, 0, sideZ, 0.01D, -sideX, 1.0D);
                    serverLevel.sendParticles(ParticleTypes.FISHING, fishX, fishY, fishZ, 0, -sideZ, 0.01D, sideX, 1.0D);
                }
            } else {
                this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.25F, 1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
                double splashY = this.getY() + 0.5D;
                serverLevel.sendParticles(ParticleTypes.BUBBLE, this.getX(), splashY, this.getZ(), (int) (1.0F + this.getBbWidth() * 20.0F), this.getBbWidth(), 0.0D, this.getBbWidth(), 0.2F);
                serverLevel.sendParticles(ParticleTypes.FISHING, this.getX(), splashY, this.getZ(), (int) (1.0F + this.getBbWidth() * 20.0F), this.getBbWidth(), 0.0D, this.getBbWidth(), 0.2F);
                this.nibbleTicks = Mth.nextInt(this.random, 20, 40);
                this.getEntityData().set(DATA_BITING, true);
            }
            return;
        }

        if (this.timeUntilLured > 0) {
            this.timeUntilLured -= biteSpeed;
            float splashChance = 0.15F;
            if (this.timeUntilLured < 20) {
                splashChance += (20 - this.timeUntilLured) * 0.05F;
            } else if (this.timeUntilLured < 40) {
                splashChance += (40 - this.timeUntilLured) * 0.02F;
            } else if (this.timeUntilLured < 60) {
                splashChance += (60 - this.timeUntilLured) * 0.01F;
            }

            if (this.random.nextFloat() < splashChance) {
                float angle = Mth.nextFloat(this.random, 0.0F, 360.0F) * ((float) Math.PI / 180F);
                float distance = Mth.nextFloat(this.random, 25.0F, 60.0F);
                double splashX = this.getX() + Mth.sin(angle) * distance * 0.1D;
                double splashY = Mth.floor(this.getY()) + 1.0F;
                double splashZ = this.getZ() + Mth.cos(angle) * distance * 0.1D;
                BlockState splashWater = serverLevel.getBlockState(BlockPos.containing(splashX, splashY - 1.0D, splashZ));
                if (splashWater.is(Blocks.WATER)) {
                    serverLevel.sendParticles(ParticleTypes.SPLASH, splashX, splashY, splashZ, 2 + this.random.nextInt(2), 0.1F, 0.0D, 0.1F, 0.0D);
                }
            }

            if (this.timeUntilLured <= 0) {
                this.fishAngle = Mth.nextFloat(this.random, 0.0F, 360.0F);
                this.timeUntilHooked = Mth.nextInt(this.random, HOOK_TIME_MIN_TICKS, HOOK_TIME_MAX_TICKS);
            }
            return;
        }

        this.timeUntilLured = Math.max(20, Mth.nextInt(this.random, LURE_TIME_MIN_TICKS, LURE_TIME_MAX_TICKS) - this.lureSpeed);
    }

    private boolean calculateOpenWater(BlockPos pos) {
        OpenWaterType openWaterType = OpenWaterType.INVALID;

        for (int yOffset = -1; yOffset <= 2; yOffset++) {
            OpenWaterType areaType = this.getOpenWaterTypeForArea(pos.offset(-2, yOffset, -2), pos.offset(2, yOffset, 2));
            switch (areaType) {
                case INVALID -> {
                    return false;
                }
                case ABOVE_WATER -> {
                    if (openWaterType == OpenWaterType.INVALID) {
                        return false;
                    }
                }
                case INSIDE_WATER -> {
                    if (openWaterType == OpenWaterType.ABOVE_WATER) {
                        return false;
                    }
                }
            }

            openWaterType = areaType;
        }

        return true;
    }

    private OpenWaterType getOpenWaterTypeForArea(BlockPos firstPos, BlockPos secondPos) {
        return BlockPos.betweenClosedStream(firstPos, secondPos)
                .map(this::getOpenWaterTypeForBlock)
                .reduce((first, second) -> first == second ? first : OpenWaterType.INVALID)
                .orElse(OpenWaterType.INVALID);
    }

    private OpenWaterType getOpenWaterTypeForBlock(BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        if (state.isAir() || state.is(Blocks.LILY_PAD)) {
            return OpenWaterType.ABOVE_WATER;
        }

        FluidState fluidState = state.getFluidState();
        return fluidState.is(FluidTags.WATER)
                && fluidState.isSource()
                && state.getCollisionShape(this.level(), pos).isEmpty()
                ? OpenWaterType.INSIDE_WATER
                : OpenWaterType.INVALID;
    }

    public boolean isReadyToCatch() {
        return this.nibbleTicks > 0;
    }

    public boolean isOpenWaterFishing() {
        return this.openWater;
    }

    public boolean isStuck() {
        return this.isRemoved()
                || this.currentState == State.HOOKED_IN_ENTITY
                || this.groundTicks > 20
                || this.outOfWaterTicks >= MAX_OUT_OF_WATER_TICKS;
    }

    public void setInUse() {
        this.keepAliveTicks = KEEP_ALIVE_TICKS;
    }

    public int retrieve(ItemStack rod) {
        PlayerNpcEntity currentAngler = this.getAngler();
        if (this.level().isClientSide() || currentAngler == null || this.shouldStopFishing(currentAngler)) {
            return 0;
        }

        int rodDamage = 0;
        if (this.hookedIn != null) {
            this.pullEntity(this.hookedIn);
            this.level().broadcastEntityEvent(this, (byte) 31);
            rodDamage = this.hookedIn instanceof ItemEntity ? 3 : 5;
        } else if (this.nibbleTicks > 0) {
            this.generateFishingLoot((ServerLevel) this.level(), currentAngler, rod);
            rodDamage = 1;
        }

        if (this.onGround()) {
            rodDamage = Math.max(rodDamage, 2);
        }

        this.discard();
        return rodDamage;
    }

    private void generateFishingLoot(ServerLevel serverLevel, PlayerNpcEntity currentAngler, ItemStack rod) {
        LootParams lootParams = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.ORIGIN, this.position())
                .withParameter(LootContextParams.TOOL, rod)
                .withParameter(LootContextParams.THIS_ENTITY, this)
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, currentAngler)
                .withLuck(this.luck)
                .create(LootContextParamSets.FISHING);
        LootTable lootTable = serverLevel.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING);
        List<ItemStack> loot = lootTable.getRandomItems(lootParams);
        for (ItemStack stack : loot) {
            this.spawnLootTowardAngler(currentAngler, stack);
        }
        serverLevel.addFreshEntity(new ExperienceOrb(serverLevel, currentAngler.getX(), currentAngler.getY() + 0.5D, currentAngler.getZ() + 0.5D, XP_PER_CATCH));
    }

    private void spawnLootTowardAngler(PlayerNpcEntity currentAngler, ItemStack stack) {
        ItemEntity itemEntity = new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), stack.copy());
        double dx = currentAngler.getX() - this.getX();
        double dy = currentAngler.getY() + 0.5D - this.getY();
        double dz = currentAngler.getZ() - this.getZ();
        itemEntity.setDeltaMovement(dx * 0.1D, dy * 0.1D + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08D, dz * 0.1D);
        this.level().addFreshEntity(itemEntity);
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == 31 && this.level().isClientSide() && this.hookedIn != null) {
            this.pullEntity(this.hookedIn);
        }

        super.handleEntityEvent(id);
    }

    protected void pullEntity(Entity entity) {
        Entity owner = this.getOwner();
        if (owner != null) {
            Vec3 pull = new Vec3(owner.getX() - this.getX(), owner.getY() - this.getY(), owner.getZ() - this.getZ()).scale(0.1D);
            entity.setDeltaMovement(entity.getDeltaMovement().add(pull));
        }
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    @Override
    protected void addAdditionalSaveData(@NotNull ValueOutput output) {
    }

    @Override
    protected void readAdditionalSaveData(@NotNull ValueInput input) {
    }

    public boolean canChangeDimensions(Level oldLevel, Level newLevel) {
        return false;
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        PlayerNpcEntity currentAngler = this.getAngler();
        buffer.writeInt(currentAngler == null ? -1 : currentAngler.getId());
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf additionalData) {
        this.anglerId = additionalData.readInt();
    }

    @Nullable
    public PlayerNpcEntity getAngler() {
        if (this.angler != null && !this.angler.isRemoved()) {
            return this.angler;
        }

        Entity owner = this.getOwner();
        if (owner instanceof PlayerNpcEntity playerNpc) {
            this.angler = playerNpc;
            this.anglerId = playerNpc.getId();
            return this.angler;
        }

        if (this.anglerId >= 0) {
            Entity entity = this.level().getEntity(this.anglerId);
            if (entity instanceof PlayerNpcEntity playerNpc) {
                this.angler = playerNpc;
                return this.angler;
            }
        }

        return null;
    }

    private enum State {
        FLYING,
        HOOKED_IN_ENTITY,
        BOBBING
    }

    private enum OpenWaterType {
        ABOVE_WATER,
        INSIDE_WATER,
        INVALID
    }
}
