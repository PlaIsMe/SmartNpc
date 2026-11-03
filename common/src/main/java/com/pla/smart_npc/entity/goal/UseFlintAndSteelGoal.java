package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

public class UseFlintAndSteelGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.using_flint_and_steel";
    private static final double MAX_TARGET_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double MAX_PLACE_DISTANCE_SQR = 5.5D * 5.5D;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private BlockPos firePos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean usingTemporaryTool;
    private int holdUntilTick;
    private int igniteAtTick;
    private LivingEntity ignitionTarget;

    public UseFlintAndSteelGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.isClearingCombatObstruction()
                || this.playerNpc.getFlintAndSteelCooldown() > 0) {
            return false;
        }

        LivingEntity target = this.playerNpc.getTarget();
        if (!this.isEligibleTarget(target)
                || !this.canUseThrottle.canCheck(this.playerNpc)
                || !this.hasFlintAndSteel()) {
            return false;
        }

        this.firePos = this.findFirePlacement(serverLevel, target);
        this.ignitionTarget = target;
        return this.firePos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return (this.igniteAtTick > 0 || this.holdUntilTick > this.playerNpc.tickCount)
                && this.playerNpc.isAlive() && !this.playerNpc.isNoAi()
                && !this.playerNpc.isClearingCombatObstruction()
                && !this.playerNpc.isPassenger() && !this.playerNpc.isHealing();
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.firePos == null
                || !this.isEligibleTarget(this.ignitionTarget)) {
            this.firePos = null;
            return;
        }

        this.holdUntilTick = 0;
        this.igniteAtTick = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        if (!this.isFlintAndSteel(this.playerNpc.getMainHandItem())) {
            ItemStack flintAndSteel = this.playerNpc.consumeInventoryItem(this::isFlintAndSteel, 1).orElse(ItemStack.EMPTY);
            if (flintAndSteel.isEmpty()) {
                this.firePos = null;
                return;
            }

            this.previousMainHand = this.playerNpc.getMainHandItem().copy();
            this.usingTemporaryTool = true;
            this.playerNpc.setMainHandItemForAi(flintAndSteel);
        }

        if (!this.canPlaceFire(serverLevel, this.firePos)) {
            this.restoreMainHand();
            this.firePos = null;
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.firePos.getX() + 0.5D,
                this.firePos.getY() + 0.5D,
                this.firePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "placing fire @ %d %d %d",
                this.firePos.getX(),
                this.firePos.getY(),
                this.firePos.getZ()
        ));
        // Allow equipment synchronization before the fire update and visible use commit.
        this.igniteAtTick = this.playerNpc.tickCount + 2;
    }

    private void ignite(ServerLevel serverLevel) {
        this.igniteAtTick = 0;
        if (!this.isEligibleTarget(this.ignitionTarget)
                || this.firePos == null
                || !this.firePos.equals(this.ignitionTarget.blockPosition())
                || !this.isFlintAndSteel(this.playerNpc.getMainHandItem())
                || !this.canPlaceFire(serverLevel, this.firePos)) {
            this.restoreMainHand();
            return;
        }
        if (!serverLevel.setBlockAndUpdate(this.firePos, Blocks.FIRE.defaultBlockState())) {
            this.restoreMainHand();
            this.firePos = null;
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
            return;
        }
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, this.firePos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        this.playerNpc.hurtMainHandItem(1);
        this.playerNpc.markCombatProgress();
        this.playerNpc.setFlintAndSteelCooldown();
        this.holdUntilTick = this.playerNpc.tickCount + 20;
    }

    @Override
    public void tick() {
        if (this.playerNpc.isClearingCombatObstruction()) {
            return;
        }
        // A server-tick deadline avoids doubling the visible hold on reduced-rate goal ticks.
        this.playerNpc.getNavigation().stop();
        if (this.igniteAtTick > 0 && this.playerNpc.tickCount >= this.igniteAtTick
                && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.ignite(serverLevel);
        }
    }

    @Override
    public void stop() {
        this.restoreMainHand();
        this.firePos = null;
        this.holdUntilTick = 0;
        this.igniteAtTick = 0;
        this.ignitionTarget = null;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private BlockPos findFirePlacement(ServerLevel serverLevel, LivingEntity target) {
        BlockPos feet = target.blockPosition();
        return this.canPlaceFire(serverLevel, feet) ? feet.immutable() : null;
    }

    private boolean isEligibleTarget(LivingEntity target) {
        return target != null && target == this.playerNpc.getTarget()
                && !this.playerNpc.isClearingCombatObstruction()
                && target != this.playerNpc && target.isAlive() && !target.isRemoved()
                && target.level() == this.playerNpc.level()
                && target.onGround() && this.playerNpc.getY() >= target.getY()
                && !target.isOnFire() && !target.fireImmune() && !target.isInWater()
                && this.playerNpc.distanceToSqr(target) <= MAX_TARGET_DISTANCE_SQR;
    }

    private boolean canPlaceFire(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.hasChunkAt(pos) || !serverLevel.hasChunkAt(pos.below())
                || !serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || this.playerNpc.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > MAX_PLACE_DISTANCE_SQR
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                || serverLevel.getBlockEntity(pos) != null) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && (state.isAir() || state.canBeReplaced())
                && Blocks.FIRE.defaultBlockState().canSurvive(serverLevel, pos);
    }

    private boolean hasFlintAndSteel() {
        return this.isFlintAndSteel(this.playerNpc.getMainHandItem())
                || InventoryUtils.hasItem(this.playerNpc, this::isFlintAndSteel);
    }

    private boolean isFlintAndSteel(ItemStack stack) {
        return !stack.isEmpty()
                && stack.is(Items.FLINT_AND_STEEL)
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    private void restoreMainHand() {
        if (!this.usingTemporaryTool) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        this.playerNpc.setMainHandItemForAi(this.previousMainHand);
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        if (!currentMainHand.isEmpty()
                && !InventoryUtils.addItem(this.playerNpc.getInventory(), currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }
    }
}
