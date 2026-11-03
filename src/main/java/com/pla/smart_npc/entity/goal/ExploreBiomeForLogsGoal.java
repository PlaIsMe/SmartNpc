package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;

public class ExploreBiomeForLogsGoal extends Goal {
    private static final int LOG_SCAN_RADIUS = 48;
    private static final int LOCAL_RESOURCE_RADIUS = 96;
    private static final int LOG_SCAN_DOWN = 2;
    private static final int LOG_SCAN_UP = 12;
    private static final int MIN_BUILD_SUPPLY = 24;
    private static final int[][] TRAVEL_RADIUS_BANDS = {
            {25, 30},
            {20, 25},
            {15, 20},
            {10, 15},
            {5, 10}
    };
    private static final int MAX_EXPLORE_TICKS = 20 * 45;
    private static final int REPATH_INTERVAL_TICKS = 20 * 3;
    private static final int LOG_SCAN_INTERVAL_TICKS = 20;
    private static final int COOLDOWN_TICKS = 20 * 6;
    private static final int FOUND_LOG_COOLDOWN_TICKS = 10;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos travelTarget;
    private int exploreTicks;
    private int repathTicks;
    private int logScanTicks;
    private boolean foundLog;

    public ExploreBiomeForLogsGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        boolean logShortage = this.hasLogShortage();
        if (!this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || (!logShortage && this.playerNpc.getBiomeExploreCooldown() > 0)
                || this.inventoryCannotAcceptLogs()
                || !this.needsSearchSupply()) {
            return false;
        }

        if (this.hasNearbyLog(serverLevel)) {
            this.playerNpc.setGatherCooldown(0);
            this.playerNpc.setBiomeExploreCooldown(FOUND_LOG_COOLDOWN_TICKS);
            return false;
        }

        this.travelTarget = this.findTravelTarget(serverLevel);
        if (this.travelTarget == null) {
            this.playerNpc.setBiomeExploreCooldown(20);
        }
        return this.travelTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.travelTarget != null
                && this.exploreTicks > 0
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && !this.foundLog
                && !this.inventoryCannotAcceptLogs()
                && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.exploreTicks = MAX_EXPLORE_TICKS;
        this.repathTicks = 0;
        this.logScanTicks = 0;
        this.foundLog = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.exploring_biome");
        this.updateTaskDetail();
        if (!this.moveToTravelTarget()) {
            this.travelTarget = null;
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.travelTarget == null) {
            return;
        }

        this.exploreTicks--;
        if (this.logScanTicks-- <= 0) {
            this.logScanTicks = LOG_SCAN_INTERVAL_TICKS;
            if (this.hasNearbyLog(serverLevel)) {
                this.foundLog = true;
                this.playerNpc.setGatherCooldown(0);
                this.playerNpc.getNavigation().stop();
                return;
            }
        }

        if (this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
            this.travelTarget = null;
            return;
        }

        if (this.repathTicks-- <= 0) {
            if (!this.moveToTravelTarget()) {
                this.travelTarget = null;
                return;
            }
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
        this.updateTaskDetail();
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide()) {
            int cooldown = this.foundLog ? FOUND_LOG_COOLDOWN_TICKS : COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 6);
            this.playerNpc.setBiomeExploreCooldown(cooldown);
        }
        this.travelTarget = null;
        this.exploreTicks = 0;
        this.repathTicks = 0;
        this.logScanTicks = 0;
        this.foundLog = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private BlockPos findTravelTarget(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        for (int[] band : TRAVEL_RADIUS_BANDS) {
            int minDistance = band[0];
            int maxDistance = band[1];
            int distance = minDistance + this.playerNpc.getRandom().nextInt(maxDistance - minDistance + 1);
            double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
            int x = (int) Math.floor(origin.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(origin.getZ() + Math.sin(angle) * distance);
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (this.canReachTravelTarget(serverLevel, candidate)) {
                return candidate.immutable();
            }
        }

        return null;
    }

    private boolean canReachTravelTarget(ServerLevel serverLevel, BlockPos candidate) {
        if (!this.canStandAt(serverLevel, candidate) || !this.isInsideResourceRadius(candidate)) {
            return false;
        }

        Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
        return path != null && path.canReach();
    }

    private boolean moveToTravelTarget() {
        if (this.travelTarget == null) {
            return false;
        }

        Path path = this.playerNpc.getNavigation().createPath(this.travelTarget, 0);
        return path != null && path.canReach() && this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private boolean hasNearbyLog(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-LOG_SCAN_RADIUS, -LOG_SCAN_DOWN, -LOG_SCAN_RADIUS),
                center.offset(LOG_SCAN_RADIUS, LOG_SCAN_UP, LOG_SCAN_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (this.isProtectedHomeBlock(immutable) || !this.isInsideResourceRadius(immutable)) {
                continue;
            }
            if (serverLevel.getBlockState(immutable).is(BlockTags.LOGS)) {
                return true;
            }
        }

        return false;
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private boolean isInsideResourceRadius(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideActivityRadius(this.playerNpc, pos, LOCAL_RESOURCE_RADIUS, true);
    }

    private boolean needsBuildSupply() {
        return this.countBuildSupply() < MIN_BUILD_SUPPLY;
    }

    private boolean needsSearchSupply() {
        return this.needsBuildSupply()
                || this.countRawLogs() < this.playerNpc.getRawLogReserveTarget()
                || this.countUsableWoodSupply() < this.playerNpc.getWoodSupplyTarget();
    }

    private boolean hasLogShortage() {
        return this.countRawLogs() < this.playerNpc.getRawLogReserveTarget()
                || this.countUsableWoodSupply() < this.playerNpc.getWoodSupplyTarget();
    }

    private boolean inventoryCannotAcceptLogs() {
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.is(ItemTags.LOGS) && stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private int countBuildSupply() {
        SimpleContainer inventory = this.playerNpc.getInventory();
        int placeableBlocks = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && this.isBuildingBlock(stack)) {
                placeableBlocks += stack.getCount();
            }
        }

        return Math.max(placeableBlocks, this.countUsableWoodSupply());
    }

    private int countUsableWoodSupply() {
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget());
    }

    private int countRawLogs() {
        int count = 0;
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.LOGS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isBuildingBlock(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem
                && !stack.is(Items.CRAFTING_TABLE)
                && !stack.is(Items.FURNACE)
                && !stack.is(Items.CHEST)
                && !(stack.getItem() instanceof BedItem);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        BlockState feet = serverLevel.getBlockState(pos);
        BlockState head = serverLevel.getBlockState(pos.above());
        BlockPos floorPos = pos.below();
        return feet.getCollisionShape(serverLevel, pos).isEmpty()
                && head.getCollisionShape(serverLevel, pos.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && serverLevel.getBlockState(floorPos).isSolidRender();
    }

    private void updateTaskDetail() {
        if (this.travelTarget == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        this.playerNpc.setCurrentAiDetail(String.format(
                Locale.ROOT,
                "searching logs toward %d %d %d raw %d/%d wood %d/%d",
                this.travelTarget.getX(),
                this.travelTarget.getY(),
                this.travelTarget.getZ(),
                this.countRawLogs(),
                this.playerNpc.getRawLogReserveTarget(),
                this.countUsableWoodSupply(),
                this.playerNpc.getWoodSupplyTarget()
        ));
    }
}
