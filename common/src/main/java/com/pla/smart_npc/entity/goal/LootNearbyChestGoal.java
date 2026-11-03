package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.event.PlayerNpcChestProtectEvent;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class LootNearbyChestGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 35;
    private static final int SEARCH_RADIUS = 10;
    private static final int SEARCH_VERTICAL_RADIUS = 3;
    private static final int MAX_SEARCH_BLOCKS_PER_PASS = 128;
    private static final double SEARCH_RESET_DISTANCE_SQR = 4.0D * 4.0D;
    private static final float PATH_NODE_MULTIPLIER = 0.35F;
    private static final double STAND_DISTANCE_SQR = 1.5D * 1.5D;
    private static final int TAKE_INTERVAL_TICKS = 6;
    private static final int FAILED_ROUTE_COOLDOWN_TICKS = 20 * 3;
    private static final int ROUTE_DOOR_CHECK_INTERVAL_TICKS = 10;
    private static final int ROUTE_CLEAR_TICKS = 20;
    private static final double ROUTE_CLEAR_DISTANCE_SQR = 12.0D * 12.0D;

    private final PlayerNpcEntity playerNpc;
    private final PathNavigationAi pathNavigationAi;
    private final ToolAi routeClearToolAi;
    private final ClearBlockAi routeClearBlockAi;
    private final double speed;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(40);
    private BlockPos chestPos;
    private BlockPos standPos;
    private boolean chestOpen;
    private boolean finishedLooting;
    private int nextLootSlot;
    private int takeDelayTicks;
    private Path plannedStandPath;
    private boolean reachedChest;
    private boolean routeFailed;
    private boolean routeClearCompleted;
    private boolean routeClearSelectionDeferred;
    private int nextRouteDoorCheckTick;
    private BlockPos pendingChestPos;
    private List<BlockPos> pendingStandCandidates = List.of();
    private int pendingStandCursor;
    private BlockPos searchOrigin;
    private int searchCursor;
    private BlockPos searchBestChest;
    private double searchBestDistance = Double.MAX_VALUE;

    public LootNearbyChestGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.routeClearToolAi = new ToolAi(playerNpc);
        this.routeClearBlockAi = new ClearBlockAi(
                playerNpc,
                new BreakingBlockAi(playerNpc, this.routeClearToolAi)
        );
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || this.playerNpc.isTeamLeaderPlayer()
                || this.playerNpc.isTeamUpRequestPending()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getLootChestCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        if (this.pendingChestPos != null) {
            return this.trySelectReachableStand(serverLevel);
        }

        ChestSearchResult search = this.findChestPass(serverLevel);
        if (!search.complete()) {
            this.canUseThrottle.retryIn(this.playerNpc, 1 + this.playerNpc.getRandom().nextInt(4));
            return false;
        }

        if (search.chest() == null) {
            return false;
        }

        this.pendingChestPos = search.chest();
        this.pendingStandCandidates = this.findStandCandidates(serverLevel, this.pendingChestPos);
        this.pendingStandCursor = 0;
        return this.trySelectReachableStand(serverLevel);
    }

    @Override
    public boolean canContinueToUse() {
        return this.chestPos != null
                && this.standPos != null
                && !this.playerNpc.isTeamLeaderPlayer()
                && !this.finishedLooting
                && !this.routeFailed
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && serverLevel.getBlockState(this.chestPos).is(Blocks.CHEST);
    }

    @Override
    public void start() {
        this.chestOpen = false;
        this.finishedLooting = false;
        this.nextLootSlot = 0;
        this.takeDelayTicks = 0;
        this.reachedChest = false;
        this.routeFailed = false;
        this.routeClearCompleted = false;
        this.nextRouteDoorCheckTick = 0;
        this.playerNpc.setCurrentAiState("ai.player_npc.looting_chest");
        if (this.chestPos != null && this.standPos != null) {
            if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
                this.openRouteDoors(serverLevel, true);
            }
            if (this.routeClearBlockAi.isRunning()) {
                this.playerNpc.setCurrentAiDetail(this.routeClearBlockAi.detail());
                return;
            }
            this.updateWalkingDetail();
            if (!(this.playerNpc.level() instanceof ServerLevel serverLevel
                    && this.canInteractWithChest(serverLevel))
                    && !this.moveToStandPos()) {
                this.failRoute("chest route expired before walking");
            }
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.chestPos == null || this.standPos == null) {
            return;
        }

        this.openRouteDoors(serverLevel, false);
        if (this.routeClearBlockAi.isRunning()) {
            boolean pathWorkAllowed = !this.routeClearBlockAi.needsPathWork(serverLevel)
                    || PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc);
            ClearBlockAi.TickResult clearResult = this.routeClearBlockAi.tick(serverLevel, pathWorkAllowed);
            if (clearResult == ClearBlockAi.TickResult.RUNNING) {
                this.playerNpc.setCurrentAiState("ai.player_npc.looting_chest");
                this.playerNpc.setCurrentAiDetail(this.routeClearBlockAi.detail());
                return;
            }
            if (clearResult == ClearBlockAi.TickResult.FAILED) {
                this.failRoute("chest route soft-obstruction clear failed");
                return;
            }
            this.routeClearCompleted = true;
        }
        if (this.routeClearCompleted && !this.resumeRouteAfterClear()) {
            return;
        }

        this.lookAtChest();
        if (!this.canInteractWithChest(serverLevel)) {
            if (this.chestOpen) {
                this.closeChest(serverLevel, this.chestPos);
                this.chestOpen = false;
            }
            this.updateWalkingDetail();
            if (this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                this.failRoute("chest route ended before adjacent stand");
            }
            return;
        }

        this.reachedChest = true;
        this.playerNpc.getNavigation().stop();
        if (serverLevel.getBlockEntity(this.chestPos) instanceof ChestBlockEntity chest && this.hasLoot(chest)) {
            if (!this.chestOpen) {
                this.openChest(serverLevel, this.chestPos);
                this.chestOpen = true;
            }

            if (this.takeDelayTicks > 0) {
                this.takeDelayTicks--;
                return;
            }

            int moved = this.lootNextStack(chest);
            if (moved > 0) {
                this.takeDelayTicks = TAKE_INTERVAL_TICKS;
                this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
                this.playerNpc.equipBetterGearFromInventory();
                serverLevel.playSound(null, this.chestPos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
                return;
            }
        }

        this.finishedLooting = true;
        if (this.chestOpen) {
            this.closeChest(serverLevel, this.chestPos);
            this.chestOpen = false;
        }
    }

    @Override
    public void stop() {
        if (this.chestOpen
                && this.chestPos != null
                && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.closeChest(serverLevel, this.chestPos);
        }
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = this.reachedChest
                    ? COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 30)
                    : FAILED_ROUTE_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 3);
            this.playerNpc.setLootChestCooldown(cooldown);
        }
        this.chestPos = null;
        this.standPos = null;
        this.chestOpen = false;
        this.finishedLooting = false;
        this.nextLootSlot = 0;
        this.takeDelayTicks = 0;
        this.plannedStandPath = null;
        this.reachedChest = false;
        this.routeFailed = false;
        this.routeClearCompleted = false;
        this.nextRouteDoorCheckTick = 0;
        this.routeClearBlockAi.stop();
        this.routeClearToolAi.restoreMainHand();
        this.clearPendingStandSelection();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private void lookAtChest() {
        if (this.chestPos == null) {
            return;
        }
        this.playerNpc.getLookControl().setLookAt(
                this.chestPos.getX() + 0.5D,
                this.chestPos.getY() + 0.5D,
                this.chestPos.getZ() + 0.5D,
                50.0F,
                50.0F
        );
    }

    private ChestSearchResult findChestPass(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        if (this.searchOrigin == null || this.searchOrigin.distSqr(center) > SEARCH_RESET_DISTANCE_SQR) {
            this.resetChestSearch(center);
        }

        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        int horizontalDiameter = SEARCH_RADIUS * 2 + 1;
        int horizontalArea = horizontalDiameter * horizontalDiameter;
        int totalPositions = horizontalArea * (SEARCH_VERTICAL_RADIUS * 2 + 1);
        int endCursor = Math.min(totalPositions, this.searchCursor + MAX_SEARCH_BLOCKS_PER_PASS);
        for (int index = this.searchCursor; index < endCursor; index++) {
            int yIndex = index / horizontalArea;
            int horizontalIndex = index % horizontalArea;
            int dx = horizontalIndex / horizontalDiameter - SEARCH_RADIUS;
            int dz = horizontalIndex % horizontalDiameter - SEARCH_RADIUS;
            int dy = yIndex - SEARCH_VERTICAL_RADIUS;
            BlockPos immutable = this.searchOrigin.offset(dx, dy, dz).immutable();
            if (!serverLevel.hasChunkAt(immutable)
                    || this.playerNpc.isOwnedChest(immutable)
                    || (homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), immutable))) {
                continue;
            }
            if (serverLevel.getBlockState(immutable).is(Blocks.CHEST)
                    && serverLevel.getBlockEntity(immutable) instanceof ChestBlockEntity chest
                    && this.hasLoot(chest)) {
                List<BlockPos> stands = this.findStandCandidates(serverLevel, immutable);
                if (stands.isEmpty()) {
                    continue;
                }
                BlockPos stand = stands.get(0);
                double distance = this.playerNpc.distanceToSqr(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
                if (distance < this.searchBestDistance) {
                    this.searchBestDistance = distance;
                    this.searchBestChest = immutable;
                }
            }
        }
        this.searchCursor = endCursor;
        if (this.searchCursor < totalPositions) {
            return new ChestSearchResult(false, null);
        }

        ChestSearchResult result = new ChestSearchResult(true, this.searchBestChest);
        this.resetChestSearch(null);
        return result;
    }

    private void resetChestSearch(BlockPos origin) {
        this.searchOrigin = origin == null ? null : origin.immutable();
        this.searchCursor = 0;
        this.searchBestChest = null;
        this.searchBestDistance = Double.MAX_VALUE;
    }

    private int lootNextStack(Container chest) {
        int size = chest.getContainerSize();
        for (int checked = 0; checked < size; checked++) {
            int slot = (this.nextLootSlot + checked) % size;
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty() || !this.canAccept(stack)) {
                continue;
            }

            int moved = this.transferSlotToInventory(chest, slot);
            this.nextLootSlot = (slot + 1) % size;
            if (moved > 0) {
                return moved;
            }
        }
        return 0;
    }

    private int transferSlotToInventory(Container chest, int chestSlot) {
        ItemStack source = chest.getItem(chestSlot);
        if (source.isEmpty()) {
            return 0;
        }

        int moved = 0;
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && !source.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()
                    || !ItemStack.isSameItemSameComponents(existing, source)
                    || existing.getCount() >= existing.getMaxStackSize()) {
                continue;
            }

            int transfer = Math.min(source.getCount(), existing.getMaxStackSize() - existing.getCount());
            existing.grow(transfer);
            source.shrink(transfer);
            moved += transfer;
        }

        for (int i = 0; i < inventory.getContainerSize() && !source.isEmpty(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                continue;
            }

            ItemStack inserted = source.copy();
            inserted.setCount(Math.min(source.getCount(), source.getMaxStackSize()));
            inventory.setItem(i, inserted);
            source.shrink(inserted.getCount());
            moved += inserted.getCount();
        }

        if (source.isEmpty()) {
            chest.setItem(chestSlot, ItemStack.EMPTY);
        }
        if (moved > 0) {
            inventory.setChanged();
            chest.setChanged();
        }
        return moved;
    }

    private void openChest(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        PlayerNpcChestProtectEvent.reportOffense(serverLevel, pos, this.playerNpc, "opened");
        serverLevel.blockEvent(pos, state.getBlock(), 1, 1);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private void closeChest(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.getBlockState(pos).is(Blocks.CHEST)) {
            return;
        }

        BlockState state = serverLevel.getBlockState(pos);
        serverLevel.blockEvent(pos, state.getBlock(), 1, 0);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private boolean hasLoot(Container chest) {
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (!stack.isEmpty() && this.canAccept(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean canAccept(ItemStack incoming) {
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(stack, incoming) && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private boolean moveToStandPos() {
        if (this.standPos == null || this.plannedStandPath == null
                || !this.pathNavigationAi.isExactPathTo(this.standPos, this.plannedStandPath)) {
            return false;
        }
        Path path = this.plannedStandPath;
        this.plannedStandPath = null;
        return this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private boolean canInteractWithChest(ServerLevel serverLevel) {
        return this.standPos != null
                && this.chestPos != null
                && this.canStandAt(serverLevel, this.standPos)
                && this.standPos.distManhattan(this.chestPos) == 1
                && this.playerNpc.distanceToSqr(this.standPos.getX() + 0.5D, this.standPos.getY(), this.standPos.getZ() + 0.5D) <= STAND_DISTANCE_SQR;
    }

    private List<BlockPos> findStandCandidates(ServerLevel serverLevel, BlockPos chestPos) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = chestPos.relative(direction).immutable();
            if (this.canStandAt(serverLevel, candidate)) {
                candidates.add(candidate);
            }
        }
        Direction facing = serverLevel.getBlockState(chestPos).hasProperty(ChestBlock.FACING)
                ? serverLevel.getBlockState(chestPos).getValue(ChestBlock.FACING)
                : null;
        BlockPos front = facing == null ? null : chestPos.relative(facing);
        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> pos.equals(front) ? 0 : 1)
                .thenComparingDouble(pos -> this.playerNpc.distanceToSqr(
                        pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D)));
        return List.copyOf(candidates);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean trySelectReachableStand(ServerLevel serverLevel) {
        if (this.pendingChestPos == null || !serverLevel.getBlockState(this.pendingChestPos).is(Blocks.CHEST)) {
            this.clearPendingStandSelection();
            return false;
        }

        this.openRouteDoors(serverLevel, true);

        while (this.pendingStandCursor < this.pendingStandCandidates.size()) {
            BlockPos candidate = this.pendingStandCandidates.get(this.pendingStandCursor);
            if (!this.canStandAt(serverLevel, candidate)) {
                this.pendingStandCursor++;
                continue;
            }
            if (this.playerNpc.distanceToSqr(
                    candidate.getX() + 0.5D,
                    candidate.getY(),
                    candidate.getZ() + 0.5D
            ) <= STAND_DISTANCE_SQR) {
                this.chestPos = this.pendingChestPos;
                this.standPos = candidate;
                this.plannedStandPath = null;
                this.clearPendingStandSelection();
                return true;
            }
            if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)) {
                this.canUseThrottle.retryIn(this.playerNpc, 1 + this.playerNpc.getRandom().nextInt(3));
                return false;
            }

            Path path = PathNavigationAi.createBoundedPath(this.playerNpc, candidate, PATH_NODE_MULTIPLIER);
            this.pendingStandCursor++;
            if (this.pathNavigationAi.isExactPathTo(candidate, path)) {
                this.chestPos = this.pendingChestPos;
                this.standPos = candidate;
                this.plannedStandPath = path;
                this.clearPendingStandSelection();
                return true;
            }

            this.canUseThrottle.retryIn(this.playerNpc, 1 + this.playerNpc.getRandom().nextInt(3));
            return false;
        }

        if (this.tryStartRouteClear(serverLevel)) {
            return true;
        }
        if (this.routeClearSelectionDeferred || this.routeClearBlockAi.hasPendingSelection()) {
            this.canUseThrottle.retryIn(this.playerNpc, 1 + this.playerNpc.getRandom().nextInt(3));
            return false;
        }
        this.clearPendingStandSelection();
        this.canUseThrottle.retryIn(this.playerNpc, FAILED_ROUTE_COOLDOWN_TICKS);
        return false;
    }

    private boolean tryStartRouteClear(ServerLevel serverLevel) {
        this.routeClearSelectionDeferred = false;
        if (this.pendingChestPos == null || this.pendingStandCandidates.isEmpty()) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)) {
            this.routeClearSelectionDeferred = true;
            this.canUseThrottle.retryIn(this.playerNpc, 1 + this.playerNpc.getRandom().nextInt(3));
            return false;
        }

        BlockPos selectedStand = this.pendingStandCandidates.get(0);
        List<BlockPos> candidates = ClearBlockAi.gatherObstructionCandidates(
                        this.playerNpc.blockPosition(),
                        selectedStand,
                        this.pendingChestPos
                ).stream()
                .map(BlockPos::immutable)
                .distinct()
                .filter(pos -> this.isSafeRouteClearPosition(serverLevel, pos))
                .toList();
        if (!this.routeClearBlockAi.startNearest(
                serverLevel,
                candidates,
                LootNearbyChestGoal::isSoftRouteObstruction,
                "clearing soft chest route obstruction",
                ROUTE_CLEAR_TICKS,
                ROUTE_CLEAR_DISTANCE_SQR
        )) {
            return false;
        }

        this.chestPos = this.pendingChestPos;
        this.standPos = selectedStand;
        this.plannedStandPath = null;
        this.clearPendingStandSelection();
        return true;
    }

    private boolean resumeRouteAfterClear() {
        if (this.standPos == null) {
            this.failRoute("chest route clear lost adjacent stand");
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.playerNpc)) {
            this.playerNpc.setCurrentAiDetail("waiting to repath cleared chest route");
            return false;
        }
        Path path = PathNavigationAi.createBoundedPath(this.playerNpc, this.standPos, PATH_NODE_MULTIPLIER);
        if (!this.pathNavigationAi.isExactPathTo(this.standPos, path)
                || !this.playerNpc.getNavigation().moveTo(path, this.speed)) {
            this.failRoute("chest route still blocked after safe clear");
            return false;
        }
        this.routeClearCompleted = false;
        this.updateWalkingDetail();
        return true;
    }

    private boolean isSafeRouteClearPosition(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.hasChunkAt(pos)
                || serverLevel.getBlockEntity(pos) != null
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || PlayerNpcHomeUtil.getHome(this.playerNpc)
                .filter(home -> PlayerNpcHomeUtil.isInside(home, pos))
                .isPresent()) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return !(state.getBlock() instanceof DoorBlock)
                && !state.is(Blocks.CHEST)
                && isSoftRouteObstruction(state);
    }

    private static boolean isSoftRouteObstruction(BlockState state) {
        return !state.isAir()
                && (state.canBeReplaced()
                || state.is(BlockTags.LEAVES)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY));
    }

    private void openRouteDoors(ServerLevel serverLevel, boolean forceCheck) {
        if (this.chestPos == null && this.pendingChestPos == null) {
            return;
        }
        if (!forceCheck && this.playerNpc.tickCount < this.nextRouteDoorCheckTick) {
            return;
        }
        this.nextRouteDoorCheckTick = this.playerNpc.tickCount + ROUTE_DOOR_CHECK_INTERVAL_TICKS;
        BlockPos from = this.playerNpc.blockPosition();
        BlockPos to = this.chestPos == null ? this.pendingChestPos : this.chestPos;
        BlockPos min = new BlockPos(
                Math.min(from.getX(), to.getX()) - 1,
                Math.min(from.getY(), to.getY()) - 1,
                Math.min(from.getZ(), to.getZ()) - 1
        );
        BlockPos max = new BlockPos(
                Math.max(from.getX(), to.getX()) + 1,
                Math.max(from.getY(), to.getY()) + 2,
                Math.max(from.getZ(), to.getZ()) + 1
        );
        for (BlockPos mutablePos : BlockPos.betweenClosed(min, max)) {
            BlockPos pos = mutablePos.immutable();
            if (distanceToRouteSqr(from, to, pos) > 2.25D || !serverLevel.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(pos);
            if (state.is(BlockTags.WOODEN_DOORS)
                    && state.getBlock() instanceof DoorBlock doorBlock
                    && state.hasProperty(DoorBlock.OPEN)
                    && !state.getValue(DoorBlock.OPEN)) {
                doorBlock.setOpen(this.playerNpc, serverLevel, state, pos, true);
            }
        }
    }

    private static double distanceToRouteSqr(BlockPos from, BlockPos to, BlockPos pos) {
        double ax = from.getX() + 0.5D;
        double ay = from.getY() + 0.5D;
        double az = from.getZ() + 0.5D;
        double bx = to.getX() + 0.5D;
        double by = to.getY() + 0.5D;
        double bz = to.getZ() + 0.5D;
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthSqr = dx * dx + dy * dy + dz * dz;
        double projection = lengthSqr <= 1.0E-6D ? 0.0D
                : Math.max(0.0D, Math.min(1.0D,
                ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / lengthSqr));
        double closestX = ax + projection * dx;
        double closestY = ay + projection * dy;
        double closestZ = az + projection * dz;
        double offsetX = px - closestX;
        double offsetY = py - closestY;
        double offsetZ = pz - closestZ;
        return offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
    }

    private void clearPendingStandSelection() {
        this.pendingChestPos = null;
        this.pendingStandCandidates = List.of();
        this.pendingStandCursor = 0;
        this.routeClearSelectionDeferred = false;
    }

    private void updateWalkingDetail() {
        if (this.chestPos == null || this.standPos == null) {
            return;
        }
        this.playerNpc.setCurrentAiState("ai.player_npc.looting_chest");
        this.playerNpc.setCurrentAiDetail("walking to chest @ "
                + this.chestPos.getX() + " " + this.chestPos.getY() + " " + this.chestPos.getZ()
                + " via " + this.standPos.getX() + " " + this.standPos.getY() + " " + this.standPos.getZ());
    }

    private void failRoute(String detail) {
        this.routeFailed = true;
        this.finishedLooting = true;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.looting_chest");
        this.playerNpc.setCurrentAiDetail(detail);
    }

    private record ChestSearchResult(boolean complete, BlockPos chest) {
    }

}
