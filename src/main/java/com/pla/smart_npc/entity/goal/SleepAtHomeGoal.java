package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.EnumSet;
import java.util.Optional;

public class SleepAtHomeGoal extends Goal {
    private static final double BED_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int MIN_SLEEP_TICKS = 20 * 20;
    private static final int MAX_SLEEP_TICKS = 20 * 60;
    private static final int CAN_USE_CHECK_INTERVAL_TICKS = 40;
    private static final int BED_REPATH_INTERVAL_TICKS = 20;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(CAN_USE_CHECK_INTERVAL_TICKS);
    private BlockPos bedPos;
    private int sleepTicks;
    private int repathTicks;

    public SleepAtHomeGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !serverLevel.isDarkOutside()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.isSleeping()
                || this.playerNpc.getSleepCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)
                || this.playerNpc.getRandom().nextFloat() > 0.35F) {
            return false;
        }
        if (TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)
                || BuildHouseGoal.hasContinuableHomeBuildWork(this.playerNpc, serverLevel)) {
            return false;
        }

        this.bedPos = this.findHomeBed(serverLevel);
        return this.bedPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.sleepTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && serverLevel.isDarkOutside()
                && this.bedPos != null
                && this.isValidBed(serverLevel, this.bedPos);
    }

    @Override
    public void start() {
        this.sleepTicks = MIN_SLEEP_TICKS + this.playerNpc.getRandom().nextInt(MAX_SLEEP_TICKS - MIN_SLEEP_TICKS + 1);
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState("ai.player_npc.sleeping");
        this.moveToBed();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || this.bedPos == null
                || !this.isValidBed(serverLevel, this.bedPos)) {
            this.wakeFromInvalidBed();
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.bedPos.getX() + 0.5D, this.bedPos.getY() + 0.5D, this.bedPos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(this.bedPos.getX() + 0.5D, this.bedPos.getY(), this.bedPos.getZ() + 0.5D) > BED_DISTANCE_SQR) {
            if (this.repathTicks-- <= 0) {
                this.moveToBed();
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (!this.playerNpc.isSleeping()) {
            this.startSleepingInBed(serverLevel);
        }
        if (this.playerNpc.isSleeping()) {
            // Epic Fight compatibility is disabled.
        }
        this.sleepTicks--;
    }

    @Override
    public void stop() {
        this.stopSleeping();
        if (!this.playerNpc.level().isClientSide()) {
            this.playerNpc.setSleepCooldown(20 * 90 + this.playerNpc.getRandom().nextInt(20 * 120));
        }
        this.bedPos = null;
        this.sleepTicks = 0;
        this.repathTicks = 0;
        if (this.playerNpc.getTarget() == null) {
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        } else {
            this.playerNpc.setCurrentAiState("ai.player_npc.retaliating");
        }
    }

    private void moveToBed() {
        if (this.bedPos != null) {
            this.playerNpc.getNavigation().moveTo(this.bedPos.getX() + 0.5D, this.bedPos.getY(), this.bedPos.getZ() + 0.5D, 1.0D);
            this.repathTicks = BED_REPATH_INTERVAL_TICKS;
        }
    }

    private void wakeFromInvalidBed() {
        this.stopSleeping();
        this.playerNpc.setCurrentAiDetail("bed missing");
        this.bedPos = null;
        this.sleepTicks = 0;
    }

    private void startSleepingInBed(ServerLevel serverLevel) {
        if (this.bedPos == null || !this.isValidBed(serverLevel, this.bedPos)) {
            this.wakeFromInvalidBed();
            return;
        }

        BlockState foot = serverLevel.getBlockState(this.bedPos);
        Direction facing = foot.getValue(BedBlock.FACING);
        BlockPos headPos = this.bedPos.relative(facing);
        if (!serverLevel.getBlockState(headPos).getBlock().equals(foot.getBlock())) {
            this.wakeFromInvalidBed();
            return;
        }

        this.playerNpc.setYRot(facing.toYRot());
        this.playerNpc.yBodyRot = facing.toYRot();
        this.playerNpc.yHeadRot = facing.toYRot();
        this.playerNpc.startSleeping(headPos);
        if (this.playerNpc.isSleeping()) {
            // Epic Fight compatibility is disabled.
        }
    }

    private void stopSleeping() {
        if (this.playerNpc.isSleeping()) {
            this.playerNpc.stopSleeping();
        }
        // Epic Fight compatibility is disabled.
    }

    private BlockPos findHomeBed(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return null;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            BlockPos foot = this.normalizeBedFoot(serverLevel, pos.immutable());
            if (foot != null && this.isValidBed(serverLevel, foot)) {
                return foot;
            }
        }
        return null;
    }

    private BlockPos normalizeBedFoot(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (!(state.getBlock() instanceof BedBlock)
                || !state.hasProperty(BedBlock.PART)
                || !state.hasProperty(BedBlock.FACING)) {
            return null;
        }
        if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos;
        }
        Direction facing = state.getValue(BedBlock.FACING);
        return pos.relative(facing.getOpposite());
    }

    private boolean isValidBed(ServerLevel serverLevel, BlockPos footPos) {
        BlockState foot = serverLevel.getBlockState(footPos);
        if (!(foot.getBlock() instanceof BedBlock)
                || !foot.hasProperty(BedBlock.PART)
                || !foot.hasProperty(BedBlock.FACING)
                || foot.getValue(BedBlock.PART) != BedPart.FOOT) {
            return false;
        }

        Direction facing = foot.getValue(BedBlock.FACING);
        BlockState head = serverLevel.getBlockState(footPos.relative(facing));
        return head.getBlock() == foot.getBlock()
                && head.hasProperty(BedBlock.PART)
                && head.hasProperty(BedBlock.FACING)
                && head.getValue(BedBlock.PART) == BedPart.HEAD
                && head.getValue(BedBlock.FACING) == facing;
    }
}
