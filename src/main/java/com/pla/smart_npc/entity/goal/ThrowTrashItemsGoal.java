package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import com.pla.smart_npc.util.PlayerNpcTrashUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Brief, bounded personal maintenance, deliberately not a job/worker-slot goal. */
public final class ThrowTrashItemsGoal extends Goal {
    public static final int OCCUPIED_SLOT_THRESHOLD = 25;
    private static final int CLEAR_TARGET_OCCUPIED_SLOTS = 20;
    private static final int FIRST_THROW_DELAY_TICKS = 10;
    private static final int THROW_INTERVAL_TICKS = 10;
    private static final int FINAL_BURN_DELAY_TICKS = 25;
    private static final int MAX_SESSION_TICKS = 20 * 20;
    private static final int PICKUP_SUPPRESSION_TICKS = 20 * 30;
    private final PlayerNpcEntity npc;
    private final CanUseThrottle throttle = new CanUseThrottle(100);
    private final List<ItemEntity> discarded = new ArrayList<>();
    private Vec3 disposalPoint;
    private int selectedSlot = -1;
    private int nextEmergencyCheckTick;
    private int elapsed;
    private int clearTargetOccupiedSlots;
    private int nextThrowTick;
    private int burnAtTick = -1;
    private boolean chatEmitted;
    private boolean sessionComplete;

    public ThrowTrashItemsGoal(PlayerNpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!isSafeToPause()) return false;
        SimpleContainer inventory = npc.getInventory();
        int occupiedSlots = PlayerNpcTrashUtil.occupiedSlots(inventory);
        if (occupiedSlots < OCCUPIED_SLOT_THRESHOLD) return false;
        boolean completelyFull = occupiedSlots >= inventory.getContainerSize();
        if (completelyFull) {
            if (npc.tickCount < nextEmergencyCheckTick) return false;
            nextEmergencyCheckTick = npc.tickCount + 20;
        } else if (!throttle.canCheck(npc)) {
            return false;
        }
        selectedSlot = PlayerNpcTrashUtil.findTrashSlot(
                inventory, npc.getMainHandItem(), npc.getOffhandItem());
        disposalPoint = selectedSlot >= 0 ? findDisposalPoint() : null;
        return disposalPoint != null;
    }

    private boolean isSafeToPause() {
        return npc.level() instanceof ServerLevel && npc.isAlive() && !npc.isNoAi()
                && npc.onGround() && !npc.isPassenger() && !npc.isSleeping()
                && !npc.isInWater() && !npc.isInLava() && !npc.isOnFire()
                && npc.getTarget() == null && !npc.isHealing()
                && npc.getUpwardEscapeTarget() == null && !npc.isTeamUpRequestPending();
    }

    private Vec3 findDisposalPoint() {
        // Four nearby loaded cells only: no pathfinding or terrain-wide inventory cleanup search.
        Direction forward = npc.getDirection();
        Direction[] directions = {forward, forward.getClockWise(), forward.getCounterClockWise(), forward.getOpposite()};
        for (Direction direction : directions) {
            BlockPos pos = npc.blockPosition().relative(direction, 2);
            if (!npc.level().hasChunkAt(pos) || !npc.level().hasChunkAt(pos.below())
                    || !npc.level().getBlockState(pos).isAir()
                    || !npc.level().getBlockState(pos.above()).isAir()
                    || !npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), Direction.UP)
                    || !npc.level().getFluidState(pos.below()).isEmpty()) continue;
            Vec3 point = Vec3.atBottomCenterOf(pos).add(0, 0.15D, 0);
            if (npc.level().clip(new ClipContext(npc.getEyePosition(), point,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, npc)).getType() == HitResult.Type.MISS) return point;
        }
        // Throwing an item entity does not require a stand or world mutation. Foliage and uneven
        // terrain can invalidate all four preferred landing cells, so retain a short loaded,
        // collision-free point in front of the NPC rather than blocking cleanup forever.
        Vec3 look = npc.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 1.0E-4D) {
            horizontal = Vec3.atLowerCornerOf(npc.getDirection().getUnitVec3i());
        }
        horizontal = horizontal.normalize();
        Vec3 origin = npc.getEyePosition().add(0.0D, -0.3D, 0.0D);
        Vec3 fallback = origin.add(horizontal.scale(1.25D)).add(0.0D, 0.15D, 0.0D);
        BlockPos fallbackPos = BlockPos.containing(fallback);
        if (npc.level().hasChunkAt(fallbackPos)
                && npc.level().getBlockState(fallbackPos).getCollisionShape(npc.level(), fallbackPos).isEmpty()
                && npc.level().getFluidState(fallbackPos).isEmpty()
                && npc.level().clip(new ClipContext(origin, fallback,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, npc)).getType() == HitResult.Type.MISS) {
            return fallback;
        }
        Vec3 closeFallback = origin.add(0.0D, 0.2D, 0.0D);
        BlockPos closePos = BlockPos.containing(closeFallback);
        return npc.level().hasChunkAt(closePos)
                && npc.level().getBlockState(closePos).getCollisionShape(npc.level(), closePos).isEmpty()
                && npc.level().getFluidState(closePos).isEmpty()
                ? closeFallback
                : null;
    }

    @Override
    public void start() {
        elapsed = 0;
        clearTargetOccupiedSlots = Math.min(CLEAR_TARGET_OCCUPIED_SLOTS,
                npc.getInventory().getContainerSize());
        nextThrowTick = FIRST_THROW_DELAY_TICKS;
        burnAtTick = -1;
        chatEmitted = false;
        sessionComplete = false;
        discarded.clear();
        npc.getNavigation().stop();
        npc.setCurrentAiState("ai.player_npc.throwing_trash");
        throttle.retryIn(npc, 20 * 30);
    }

    @Override
    public boolean canContinueToUse() {
        return !sessionComplete && elapsed < MAX_SESSION_TICKS && isSafeToPause();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        if (disposalPoint != null) {
            npc.getLookControl().setLookAt(disposalPoint.x, disposalPoint.y, disposalPoint.z, 30, 30);
        }
        elapsed++;
        if (burnAtTick >= 0) {
            if (elapsed >= burnAtTick) {
                burnBatch();
                sessionComplete = true;
            }
            return;
        }
        if (elapsed >= nextThrowTick) {
            throwNextTrashStack();
            nextThrowTick = elapsed + THROW_INTERVAL_TICKS;
        }
    }

    private void throwNextTrashStack() {
        SimpleContainer inventory = npc.getInventory();
        if (PlayerNpcTrashUtil.occupiedSlots(inventory) <= clearTargetOccupiedSlots) {
            beginFinishingSession();
            return;
        }

        // Recompute one inventory profile and selection so hand/inventory mutations between
        // actions cannot cause a newly protected item to be discarded.
        selectedSlot = PlayerNpcTrashUtil.findTrashSlot(
                inventory, npc.getMainHandItem(), npc.getOffhandItem());
        if (selectedSlot < 0) {
            beginFinishingSession();
            return;
        }

        disposalPoint = findDisposalPoint();
        if (disposalPoint == null) {
            beginFinishingSession();
            return;
        }

        Vec3 origin = npc.getEyePosition().add(0, -0.3D, 0);
        Vec3 velocity = disposalPoint.subtract(origin).multiply(0.16D, 0, 0.16D).add(0, 0.12D, 0);
        ItemStack stack = inventory.getItem(selectedSlot);
        ItemEntity drop = new ItemEntity(npc.level(), origin.x, origin.y, origin.z,
                PlayerNpcTrashUtil.discardedCopy(stack));
        drop.setDeltaMovement(velocity);
        drop.setPickUpDelay(40);
        // Do not lose inventory contents if spawning is rejected by the world/another mod.
        if (npc.level().addFreshEntity(drop)) {
            inventory.setItem(selectedSlot, ItemStack.EMPTY);
            discarded.add(drop);
            npc.suppressItemPickupFor(PICKUP_SUPPRESSION_TICKS);
            npc.swing(InteractionHand.MAIN_HAND);
            if (!chatEmitted) {
                ChatUtil.throwTrash(npc);
                chatEmitted = true;
            }
            if (PlayerNpcTrashUtil.occupiedSlots(inventory) <= clearTargetOccupiedSlots) {
                beginFinishingSession();
            }
        } else {
            // Keep the inventory transactional, but do not hold MOVE indefinitely if another mod
            // rejects the spawned ItemEntity.
            beginFinishingSession();
        }
    }

    private void beginFinishingSession() {
        if (discarded.isEmpty()) {
            sessionComplete = true;
            return;
        }
        if (burnAtTick < 0) {
            burnAtTick = elapsed + FINAL_BURN_DELAY_TICKS;
        }
    }

    private ItemStack findFlintAndSteel() {
        if (npc.getMainHandItem().is(Items.FLINT_AND_STEEL)) return npc.getMainHandItem();
        if (npc.getOffhandItem().is(Items.FLINT_AND_STEEL)) return npc.getOffhandItem();
        for (int slot = 0; slot < npc.getInventory().getContainerSize(); slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.is(Items.FLINT_AND_STEEL)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private void burnBatch() {
        ItemStack flint = findFlintAndSteel();
        if (flint.isEmpty() || !(npc.level() instanceof ServerLevel level)) return;
        boolean burned = false;
        // Only this batch: no world fire, no nearby loot queries, no damage to players or buildings.
        for (ItemEntity drop : discarded) {
            if (!drop.isAlive() || !drop.onGround() || drop.isInWater()
                    || npc.distanceToSqr(drop) > 16 || !PlayerNpcTrashUtil.isDiscarded(drop.getItem())) continue;
            level.sendParticles(ParticleTypes.FLAME, drop.getX(), drop.getY() + 0.1D, drop.getZ(), 6, 0.1, 0.1, 0.1, 0.01);
            level.sendParticles(ParticleTypes.SMOKE, drop.getX(), drop.getY() + 0.2D, drop.getZ(), 4, 0.1, 0.1, 0.1, 0.01);
            drop.igniteForSeconds(2.0F);
            drop.hurt(level.damageSources().inFire(), 5.0F);
            burned = true;
        }
        if (burned) {
            level.playSound(null, npc.blockPosition(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.NEUTRAL, 0.7F, 1.0F);
            EquipmentSlot flintSlot = flint == npc.getMainHandItem()
                    ? EquipmentSlot.MAINHAND
                    : flint == npc.getOffhandItem() ? EquipmentSlot.OFFHAND : null;
            flint.hurtAndBreak(1, level, npc, item -> {
                if (flintSlot != null) npc.onEquippedItemBroken(item, flintSlot);
            });
            npc.getInventory().setChanged();
        }
    }

    @Override
    public void stop() {
        discarded.clear();
        disposalPoint = null;
        selectedSlot = -1;
        clearTargetOccupiedSlots = 0;
        nextThrowTick = 0;
        burnAtTick = -1;
        chatEmitted = false;
        sessionComplete = false;
        npc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }
}
