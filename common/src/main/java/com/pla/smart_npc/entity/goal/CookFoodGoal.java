package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.FurnaceAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Plan;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class CookFoodGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 20;
    private static final int FAIL_COOLDOWN_TICKS = 20 * 4;
    private static final int FURNACE_SCAN_RADIUS = 5;
    private static final double HOME_ACTION_DISTANCE_SQR = 8.0D * 8.0D;
    private static final double FURNACE_USE_DISTANCE_SQR = 2.25D * 2.25D;
    private static final double FURNACE_STAND_REACHED_SQR = 1.25D * 1.25D;
    private static final double RECOVER_FURNACE_BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double RECOVER_FURNACE_MOVE_SPEED = 1.0D;
    private static final int ACTION_DELAY_TICKS = 12;
    private static final int MAX_RECOVER_FURNACE_TICKS = 20 * 10;
    private static final int MAX_COOK_TICKS = 20 * 30;
    private static final int MOVEMENT_REPATH_TICKS = 20;
    private static final float FURNACE_PATH_NODE_MULTIPLIER = 0.01F;

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final FurnaceAi furnaceAi;
    private final PlacingBlockAi placingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos furnacePos;
    private BlockPos furnaceStandPos;
    private Mode mode = Mode.INTERACT;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int actionDelayTicks;
    private int cookTicks;
    private int movementRepathTicks;
    private boolean finished;
    private boolean acted;
    private boolean temporaryFurnace;
    private boolean usingTemporaryTool;
    private boolean returnTemporaryMainHandOnRestore;
    private String furnaceWorkReason = "unknown";

    public CookFoodGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.furnaceAi = new FurnaceAi(playerNpc);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getCookFoodCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (this.shouldDeferForPrimarySupply(serverLevel)) {
            return false;
        }
        if (this.shouldDeferForFishingNightCamp()) {
            return false;
        }

        this.resetPlan();
        this.homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc).orElse(null);

        BlockPos temporary = this.getTemporaryFurnacePos();
        if (temporary != null) {
            if (serverLevel.getBlockState(temporary).is(Blocks.FURNACE)
                    && serverLevel.getBlockEntity(temporary) instanceof FurnaceBlockEntity furnace) {
                boolean correctBlueprintFurnace = this.isCorrectBlueprintFurnace(serverLevel, temporary);
                if (this.isInsideActiveBuildFootprint(temporary) && correctBlueprintFurnace) {
                    // A later build pass may have adopted this block as the layout's real furnace.
                    // Stop treating it as disposable so recovery can never remove correct work.
                    this.clearTemporaryFurnace();
                    if (this.furnaceAi.hasFurnaceWork(serverLevel, furnace)) {
                        return this.planInteraction(serverLevel, temporary, false);
                    }
                    return false;
                } else if (this.isInsideOwnedFarmFurnaceExclusion(temporary)
                        || this.isInsideActiveBuildFootprint(temporary)) {
                    return this.planRecovery(serverLevel, temporary);
                }
                if (this.furnaceAi.hasFurnaceWork(serverLevel, furnace)) {
                    return this.planInteraction(serverLevel, temporary, true);
                }
                if (this.furnaceAi.isFurnaceEmpty(furnace)) {
                    return this.planRecovery(serverLevel, temporary);
                }
                return false;
            }
            this.clearTemporaryFurnace();
        }

        if (this.homeArea != null && this.isNearHome()) {
            BlockPos homeFurnace = this.findHomeFurnace(serverLevel);
            if (homeFurnace != null && serverLevel.getBlockEntity(homeFurnace) instanceof FurnaceBlockEntity furnace) {
                if (this.furnaceAi.hasFurnaceWork(serverLevel, furnace) && this.planInteraction(serverLevel, homeFurnace, false)) {
                    return true;
                }
            }

            BlockPos placement = this.findHomeFurnacePlacement(serverLevel);
            if (placement != null && this.furnaceAi.shouldPlaceFurnaceForWork(serverLevel)) {
                return this.planPlacement(serverLevel, placement, Mode.PLACE_TEMPORARY, true);
            }
        }

        BlockPos nearbyFurnace = this.findNearbyFurnace(serverLevel);
        if (nearbyFurnace != null && serverLevel.getBlockEntity(nearbyFurnace) instanceof FurnaceBlockEntity furnace) {
            if (this.furnaceAi.hasFurnaceWork(serverLevel, furnace) && this.planInteraction(serverLevel, nearbyFurnace, false)) {
                return true;
            }
        }

        if (!this.furnaceAi.shouldPlaceFurnaceForWork(serverLevel)) {
            return false;
        }

        BlockPos placement = this.findTemporaryFurnacePlacement(serverLevel);
        return placement != null && this.planPlacement(serverLevel, placement, Mode.PLACE_TEMPORARY, true);
    }

    private boolean shouldDeferForPrimarySupply(ServerLevel serverLevel) {
        if (GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc)) {
            return true;
        }
        return !serverLevel.isDarkOutside()
                && !serverLevel.isThundering()
                && (GatherLogsGoal.hasLogSupplyDemand(this.playerNpc, serverLevel)
                || GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel));
    }

    private boolean shouldDeferForFishingNightCamp() {
        return this.playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING);
    }

    @Override
    public boolean canContinueToUse() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && !this.shouldDeferForPrimarySupply(serverLevel)
                && !this.finished
                && this.furnacePos != null
                && this.cookTicks < MAX_COOK_TICKS
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        this.actionDelayTicks = 0;
        this.cookTicks = 0;
        this.movementRepathTicks = 0;
        this.finished = false;
        this.acted = false;
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.cooking");
        this.updateDetail(null);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.furnacePos == null) {
            this.finished = true;
            return;
        }

        this.cookTicks++;
        if (this.mode == Mode.RECOVER_TEMPORARY) {
            this.tickRecoverTemporaryFurnace(serverLevel);
            return;
        }

        if (this.mode == Mode.PLACE_HOME || this.mode == Mode.PLACE_TEMPORARY) {
            this.tickPlaceFurnace(serverLevel);
            return;
        }

        if (!serverLevel.getBlockState(this.furnacePos).is(Blocks.FURNACE)
                || !(serverLevel.getBlockEntity(this.furnacePos) instanceof FurnaceBlockEntity furnace)) {
            this.finished = true;
            return;
        }

        if (!this.ensureFurnaceStand(serverLevel)) {
            this.finished = true;
            return;
        }

        this.lookAtFurnace();
        if (!this.isAtFurnaceStand()) {
            this.playerNpc.setCurrentAiDetail("walking to furnace");
            if (!this.moveToFurnaceStand()) {
                this.finished = true;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.updateDetail(serverLevel);
            return;
        }
        this.actionDelayTicks = 0;

        boolean moved = this.furnaceAi.takeOutput(serverLevel, this.furnacePos, furnace)
                || this.furnaceAi.fillFurnace(serverLevel, this.furnacePos, furnace);
        this.acted |= moved;
        this.finished = true;
    }

    @Override
    public void stop() {
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.restorePreviousMainHand();
        int cooldown = this.acted
                ? COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 20)
                : FAIL_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 4);
        this.playerNpc.setCookFoodCooldown(cooldown);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
    }

    private boolean planInteraction(ServerLevel serverLevel, BlockPos pos, boolean temporary) {
        BlockPos stand = this.findFurnaceStand(serverLevel, pos);
        if (stand == null) {
            return false;
        }
        this.mode = Mode.INTERACT;
        this.furnacePos = pos.immutable();
        this.furnaceStandPos = stand;
        this.temporaryFurnace = temporary;
        return true;
    }

    private boolean planPlacement(ServerLevel serverLevel, BlockPos pos, Mode mode, boolean temporary) {
        BlockPos stand = this.findFurnaceStand(serverLevel, pos);
        if (stand == null) {
            return false;
        }
        this.mode = mode;
        this.furnacePos = pos.immutable();
        this.furnaceStandPos = stand;
        this.temporaryFurnace = temporary;
        this.furnaceWorkReason = this.furnaceAi.describePendingWork(serverLevel);
        return true;
    }

    private boolean planRecovery(ServerLevel serverLevel, BlockPos pos) {
        this.mode = Mode.RECOVER_TEMPORARY;
        this.furnacePos = pos.immutable();
        this.furnaceStandPos = this.findFurnaceStand(serverLevel, pos);
        this.temporaryFurnace = true;
        return this.furnaceStandPos != null;
    }

    private void tickPlaceFurnace(ServerLevel serverLevel) {
        if (!this.ensureFurnaceStand(serverLevel)) {
            this.finished = true;
            return;
        }

        this.lookAtFurnace();
        if (!this.isAtFurnaceStand()) {
            this.playerNpc.setCurrentAiDetail(this.placementDetail("walking to furnace placement"));
            if (!this.moveToFurnaceStand()) {
                this.finished = true;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail(this.placementDetail("preparing furnace"));
            return;
        }
        this.actionDelayTicks = 0;

        ItemStack furnace = this.takeOrCraftFurnace();
        if (furnace.isEmpty()) {
            this.finished = true;
            return;
        }

        if (!this.canPlaceFurnaceAt(serverLevel, this.furnacePos)) {
            this.returnStack(furnace);
            this.finished = true;
            return;
        }

        this.showPlacementItem(furnace);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.furnacePos, Blocks.FURNACE.defaultBlockState())) {
            this.returnStack(furnace);
            this.finished = true;
            return;
        }
        this.finishPlacementMainHand();
        if (this.temporaryFurnace) {
            this.saveTemporaryFurnace(this.furnacePos);
        }
        this.acted = true;
        this.mode = Mode.INTERACT;
        this.actionDelayTicks = 0;
        this.furnaceStandPos = this.findFurnaceStand(serverLevel, this.furnacePos);
        if (this.furnaceStandPos == null) {
            this.finished = true;
        }
    }

    private void tickRecoverTemporaryFurnace(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.furnacePos);
        if (!state.is(Blocks.FURNACE)) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.clearTemporaryFurnace();
            this.acted = true;
            this.finished = true;
            return;
        }

        if (this.isInsideOwnedFarmFurnaceExclusion(this.furnacePos)) {
            if (!this.ensureFurnaceStand(serverLevel) || !this.isAtFurnaceStand()) {
                this.breakingBlockAi.stop();
                this.toolAi.restoreMainHand();
                this.playerNpc.setCurrentAiDetail("recovering misplaced farm furnace");
                if (!this.moveToFurnaceStand()) {
                    this.finished = true;
                }
                return;
            }
            this.playerNpc.getNavigation().stop();
            this.acted = this.packMisplacedTemporaryFurnace(serverLevel);
            this.finished = true;
            return;
        }

        if (serverLevel.getBlockEntity(this.furnacePos) instanceof FurnaceBlockEntity furnace && !this.furnaceAi.isFurnaceEmpty(furnace)) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            this.finished = true;
            return;
        }

        this.lookAtFurnace();
        if (this.playerNpc.distanceToSqr(
                this.furnacePos.getX() + 0.5D,
                this.furnacePos.getY() + 0.5D,
                this.furnacePos.getZ() + 0.5D
        ) > RECOVER_FURNACE_BREAK_DISTANCE_SQR) {
            this.breakingBlockAi.stop();
            this.toolAi.restoreMainHand();
            if (this.movementRepathTicks-- <= 0) {
                this.playerNpc.getNavigation().moveTo(
                        this.furnacePos.getX() + 0.5D,
                        this.furnacePos.getY(),
                        this.furnacePos.getZ() + 0.5D,
                        RECOVER_FURNACE_MOVE_SPEED
                );
                this.movementRepathTicks = MOVEMENT_REPATH_TICKS;
            }
            this.updateDetail(serverLevel);
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.toolAi.equipBestToolFor(state);
        boolean dropRecoveredBlock = PlayerNpcBlockBreakUtil.shouldDropResources(state, this.playerNpc.getMainHandItem());
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.furnacePos,
                candidate -> candidate.is(Blocks.FURNACE),
                MAX_RECOVER_FURNACE_TICKS,
                "recovering furnace",
                true
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        BlockPos recoveredPos = this.furnacePos;
        if (result == BreakingBlockAi.TickResult.DONE) {
            if (!dropRecoveredBlock) {
                this.returnStack(new ItemStack(Items.FURNACE));
            }
            this.clearTemporaryFurnace();
            this.acted = true;
            this.finished = true;
            return;
        }

        this.playerNpc.clearBlockBreakProgress(recoveredPos);
        this.clearTemporaryFurnace();
        this.finished = true;
    }

    private boolean takeCookedOutput(ServerLevel serverLevel, FurnaceBlockEntity furnace) {
        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) {
            return false;
        }

        ItemStack moved = output.copy();
        furnace.setItem(2, ItemStack.EMPTY);
        furnace.setChanged();
        if (!InventoryUtils.addItem(this.playerNpc, moved)) {
            this.playerNpc.spawnAtLocation(moved);
        }
        serverLevel.playSound(null, this.furnacePos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
        this.playerNpc.setCurrentAiDetail("taking furnace output");
        return true;
    }

    private boolean fillFurnace(ServerLevel serverLevel, FurnaceBlockEntity furnace) {
        boolean movedAny = false;
        if (furnace.getItem(0).isEmpty()) {
            ItemStack input = this.takeGlassSandInput(serverLevel)
                    .or(() -> this.playerNpc.consumeInventoryItem(this::isCookableFood, 1))
                    .or(() -> this.playerNpc.consumeInventoryItem(this::isSmeltableMaterial, 1))
                    .orElse(ItemStack.EMPTY);
            if (!input.isEmpty()) {
                furnace.setItem(0, input);
                movedAny = true;
            }
        }

        if (furnace.getItem(1).isEmpty()) {
            ItemStack fuel = this.playerNpc.consumeInventoryItem(this::isFuel, 1).orElse(ItemStack.EMPTY);
            if (!fuel.isEmpty()) {
                furnace.setItem(1, fuel);
                movedAny = true;
            }
        }

        if (movedAny) {
            furnace.setChanged();
            serverLevel.playSound(null, this.furnacePos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
            this.playerNpc.setCurrentAiDetail("loading furnace");
        }
        return movedAny;
    }

    private java.util.Optional<ItemStack> takeGlassSandInput(ServerLevel serverLevel) {
        int missingGlass = PlayerNpcBuildMaterialUtil.missingGlassForProduction(serverLevel, this.playerNpc);
        if (missingGlass <= 0) {
            return java.util.Optional.empty();
        }

        return this.playerNpc.consumeInventoryItem(
                PlayerNpcBuildMaterialUtil::isGlassSmeltingInput,
                Math.min(missingGlass, 64)
        );
    }

    private BlockPos findHomeFurnace(ServerLevel serverLevel) {
        if (this.homeArea == null) {
            return null;
        }

        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin(),
                this.homeArea.origin().offset(this.homeArea.width() - 1, 3, this.homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).is(Blocks.FURNACE)
                    && this.canUseExistingFurnace(serverLevel, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findNearbyFurnace(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-FURNACE_SCAN_RADIUS, -2, -FURNACE_SCAN_RADIUS),
                origin.offset(FURNACE_SCAN_RADIUS, 2, FURNACE_SCAN_RADIUS))) {
            if (serverLevel.getBlockState(pos).is(Blocks.FURNACE)
                    && this.canUseExistingFurnace(serverLevel, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findHomeFurnacePlacement(ServerLevel serverLevel) {
        if (this.homeArea == null) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos origin = this.homeArea.origin();
        for (int margin = 1; margin <= 3; margin++) {
            int minX = -margin;
            int maxX = this.homeArea.width() - 1 + margin;
            int minZ = -margin;
            int maxZ = this.homeArea.depth() - 1 + margin;
            for (int y = 0; y <= 1; y++) {
                for (int x = minX; x <= maxX; x++) {
                    candidates.add(origin.offset(x, y, minZ));
                    candidates.add(origin.offset(x, y, maxZ));
                }
                for (int z = minZ + 1; z < maxZ; z++) {
                    candidates.add(origin.offset(minX, y, z));
                    candidates.add(origin.offset(maxX, y, z));
                }
            }
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos pos = candidate.immutable();
            if (this.canPlaceFurnaceAt(serverLevel, pos)
                    && this.findFurnaceStand(serverLevel, pos) != null) {
                return pos;
            }
        }
        return null;
    }

    private BlockPos findTemporaryFurnacePlacement(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        Plan farmPlan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (farmPlan != null && !farmPlan.pathPositions().isEmpty()) {
            BlockPos insideGateFeet = farmPlan.pathPositions().get(0).above();
            int outwardX = Integer.compare(farmPlan.gatePos().getX(), insideGateFeet.getX());
            int outwardZ = Integer.compare(farmPlan.gatePos().getZ(), insideGateFeet.getZ());
            BlockPos outsideGateFeet = farmPlan.gatePos().offset(outwardX, 0, outwardZ);
            for (int radius = 1; radius <= 3; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        for (int dy = -1; dy <= 1; dy++) {
                            candidates.add(outsideGateFeet.offset(dx, dy, dz));
                        }
                    }
                }
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(direction));
        }
        candidates.add(center.above());
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= 1) {
                        continue;
                    }
                    BlockPos candidate = center.offset(dx, dy, dz);
                    if (!candidate.equals(center) && !candidates.contains(candidate)) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        candidates = new ArrayList<>(candidates.stream().distinct().toList());
        candidates.sort(Comparator.comparingDouble(center::distSqr));

        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canPlaceFurnaceAt(serverLevel, immutable) && this.findFurnaceStand(serverLevel, immutable) != null) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canPlaceFurnaceAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        if (this.isInsideOwnedFarmFurnaceExclusion(pos)) {
            return false;
        }
        if (this.isInsideActiveBuildFootprint(pos)) {
            return false;
        }
        return serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private boolean canUseExistingFurnace(ServerLevel serverLevel, BlockPos pos) {
        return !this.isInsideActiveBuildFootprint(pos)
                || this.isCorrectBlueprintFurnace(serverLevel, pos);
    }

    private boolean isInsideActiveBuildFootprint(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos);
    }

    private boolean isCorrectBlueprintFurnace(ServerLevel serverLevel, BlockPos pos) {
        if (this.homeArea == null || pos == null || !this.isInsideActiveBuildFootprint(pos)) {
            return false;
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(this.playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != this.homeArea.width()
                || layout.get().depth() != this.homeArea.depth()) {
            return false;
        }

        BlockState existing = serverLevel.getBlockState(pos);
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (block.toWorld(this.homeArea.origin()).equals(pos)
                    && block.state().is(Blocks.FURNACE)
                    && PlayerNpcBuildMaterialUtil.matches(existing, block.state())) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findFurnaceStand(ServerLevel serverLevel, BlockPos pos) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(pos.relative(direction));
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.canStandAt(serverLevel, immutable)
                    || this.distanceToFurnaceSqr(immutable, pos) > FURNACE_USE_DISTANCE_SQR) {
                continue;
            }
            if (immutable.equals(center)) {
                return immutable;
            }
            // Eligibility chooses a physical interaction stand only. The running movement phase
            // owns the single bounded route so canUse never builds one PathNavigationRegion per
            // furnace/placement candidate.
            return immutable;
        }
        return null;
    }

    private boolean ensureFurnaceStand(ServerLevel serverLevel) {
        if (this.furnaceStandPos != null && this.canStandAt(serverLevel, this.furnaceStandPos)) {
            return true;
        }

        this.furnaceStandPos = this.findFurnaceStand(serverLevel, this.furnacePos);
        return this.furnaceStandPos != null;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private boolean isAtFurnaceStand() {
        return this.furnaceStandPos != null
                && this.playerNpc.distanceToSqr(this.furnaceStandPos.getX() + 0.5D, this.furnaceStandPos.getY(), this.furnaceStandPos.getZ() + 0.5D) <= FURNACE_STAND_REACHED_SQR
                && this.distanceToFurnaceSqr(this.playerNpc.blockPosition(), this.furnacePos) <= FURNACE_USE_DISTANCE_SQR + 1.0D;
    }

    private boolean moveToFurnaceStand() {
        if (this.furnaceStandPos == null) {
            return false;
        }
        if (this.movementRepathTicks-- > 0) {
            return true;
        }
        this.movementRepathTicks = MOVEMENT_REPATH_TICKS;
        Path path = PathNavigationAi.createBoundedPath(
                this.playerNpc,
                this.furnaceStandPos,
                FURNACE_PATH_NODE_MULTIPLIER
        );
        if (path == null
                || !path.canReach()
                || path.getEndNode() == null
                || !path.getEndNode().asBlockPos().equals(this.furnaceStandPos)) {
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, 1.0D);
    }

    private boolean packMisplacedTemporaryFurnace(ServerLevel serverLevel) {
        if (!(serverLevel.getBlockEntity(this.furnacePos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }
        List<ItemStack> contents = new ArrayList<>(furnace.getContainerSize());
        for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
            contents.add(furnace.getItem(slot).copy());
            furnace.setItem(slot, ItemStack.EMPTY);
        }
        furnace.setChanged();
        if (!serverLevel.removeBlock(this.furnacePos, false)) {
            for (int slot = 0; slot < contents.size(); slot++) {
                furnace.setItem(slot, contents.get(slot));
            }
            furnace.setChanged();
            return false;
        }
        for (ItemStack stack : contents) {
            this.returnStack(stack);
        }
        this.returnStack(new ItemStack(Items.FURNACE));
        this.placingBlockAi.playMainHandAction();
        this.clearTemporaryFurnace();
        this.playerNpc.setCurrentAiDetail("packed misplaced farm furnace");
        return true;
    }

    private boolean isInsideOwnedFarmFurnaceExclusion(BlockPos pos) {
        return FarmAi.isProtectedFarmlandBlock(this.playerNpc, pos)
                || FarmAi.isInsideOwnedFarmWorkOrEntranceFootprint(this.playerNpc, pos);
    }

    private double distanceToFurnaceSqr(BlockPos standPos, BlockPos pos) {
        if (pos == null) {
            return Double.MAX_VALUE;
        }
        double dx = standPos.getX() + 0.5D - (pos.getX() + 0.5D);
        double dy = standPos.getY() + 0.5D - (pos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (pos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean hasFurnaceWork(FurnaceBlockEntity furnace) {
        if (!furnace.getItem(2).isEmpty()) {
            return true;
        }

        boolean hasInput = !furnace.getItem(0).isEmpty();
        boolean hasFuelInFurnace = !furnace.getItem(1).isEmpty();
        boolean hasInventoryInput = this.hasCookableFood() || this.hasSmeltableMaterial();
        boolean hasInventoryFuel = this.hasFuel();
        return hasInput && !hasFuelInFurnace && hasInventoryFuel
                || !hasInput && hasInventoryInput && (hasFuelInFurnace || hasInventoryFuel);
    }

    private boolean shouldPlaceFurnaceForWork() {
        return (InventoryUtils.hasItem(this.playerNpc, Items.FURNACE)
                || PlayerNpcCraftingUtil.canCraftFurnace(this.playerNpc.getInventory()))
                && (this.hasCookableFood() || this.hasSmeltableMaterial())
                && this.hasFuel();
    }

    private boolean isFurnaceEmpty(FurnaceBlockEntity furnace) {
        return furnace.getItem(0).isEmpty()
                && furnace.getItem(1).isEmpty()
                && furnace.getItem(2).isEmpty();
    }

    private ItemStack takeOrCraftFurnace() {
        ItemStack furnace = this.playerNpc.consumeInventoryItem(Items.FURNACE, 1).orElse(ItemStack.EMPTY);
        if (!furnace.isEmpty()) {
            return furnace;
        }

        if (!PlayerNpcCraftingUtil.tryCraftFurnace(this.playerNpc.getInventory())) {
            return ItemStack.EMPTY;
        }
        return this.playerNpc.consumeInventoryItem(Items.FURNACE, 1).orElse(ItemStack.EMPTY);
    }

    private boolean hasCookableFood() {
        return InventoryUtils.hasItem(this.playerNpc, this::isCookableFood);
    }

    private boolean hasSmeltableMaterial() {
        return InventoryUtils.hasItem(this.playerNpc, this::isSmeltableMaterial);
    }

    private boolean hasFuel() {
        return InventoryUtils.hasItem(this.playerNpc, this::isFuel);
    }

    private boolean isNearHome() {
        if (this.homeArea == null) {
            return false;
        }

        BlockPos homeCenter = this.homeArea.origin().offset(this.homeArea.width() / 2, 1, this.homeArea.depth() / 2);
        return this.playerNpc.distanceToSqr(homeCenter.getX() + 0.5D, homeCenter.getY(), homeCenter.getZ() + 0.5D) <= HOME_ACTION_DISTANCE_SQR;
    }

    private boolean isCookableFood(ItemStack stack) {
        return stack.is(Items.BEEF)
                || stack.is(Items.PORKCHOP)
                || stack.is(Items.CHICKEN)
                || stack.is(Items.MUTTON)
                || stack.is(Items.RABBIT)
                || stack.is(Items.COD)
                || stack.is(Items.SALMON)
                || stack.is(Items.POTATO);
    }

    private boolean isSmeltableMaterial(ItemStack stack) {
        return stack.is(Items.COBBLESTONE)
                || stack.is(Items.COBBLED_DEEPSLATE)
                || this.shouldSmeltSandForGlass(stack)
                || stack.is(Items.RAW_IRON)
                || stack.is(Items.RAW_COPPER)
                || stack.is(Items.RAW_GOLD)
                || stack.is(Items.IRON_ORE)
                || stack.is(Items.DEEPSLATE_IRON_ORE)
                || stack.is(Items.COPPER_ORE)
                || stack.is(Items.DEEPSLATE_COPPER_ORE)
                || stack.is(Items.GOLD_ORE)
                || stack.is(Items.DEEPSLATE_GOLD_ORE);
    }

    private boolean shouldSmeltSandForGlass(ItemStack stack) {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && PlayerNpcBuildMaterialUtil.isGlassSmeltingInput(stack)
                && PlayerNpcBuildMaterialUtil.needsGlassSmelting(serverLevel, this.playerNpc);
    }

    private boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && stack.has(net.minecraft.core.component.DataComponents.COOKING_FUEL);
    }

    private BlockPos getTemporaryFurnacePos() {
        if (FurnaceAi.TEMP_FURNACE_KIND_NIGHT_CAMP.equals(
                com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).getStringOr(FurnaceAi.TEMP_FURNACE_KIND, ""))
                || !com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).contains(FurnaceAi.TEMP_FURNACE_X)) {
            return null;
        }

        return new BlockPos(
                com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).getIntOr(FurnaceAi.TEMP_FURNACE_X, 0),
                com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).getIntOr(FurnaceAi.TEMP_FURNACE_Y, 0),
                com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).getIntOr(FurnaceAi.TEMP_FURNACE_Z, 0)
        );
    }

    private void saveTemporaryFurnace(BlockPos pos) {
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).putInt(FurnaceAi.TEMP_FURNACE_X, pos.getX());
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).putInt(FurnaceAi.TEMP_FURNACE_Y, pos.getY());
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).putInt(FurnaceAi.TEMP_FURNACE_Z, pos.getZ());
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).putString(
                FurnaceAi.TEMP_FURNACE_KIND,
                FurnaceAi.TEMP_FURNACE_KIND_COOKING
        );
    }

    private void clearTemporaryFurnace() {
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).remove(FurnaceAi.TEMP_FURNACE_X);
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).remove(FurnaceAi.TEMP_FURNACE_Y);
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).remove(FurnaceAi.TEMP_FURNACE_Z);
        com.pla.smart_npc.platform.NpcPlatform.persistentData(this.playerNpc).remove(FurnaceAi.TEMP_FURNACE_KIND);
    }

    private String placementDetail(String action) {
        return action + " (work: " + this.furnaceWorkReason + ")";
    }

    private void lookAtFurnace() {
        this.playerNpc.getLookControl().setLookAt(
                this.furnacePos.getX() + 0.5D,
                this.furnacePos.getY() + 0.5D,
                this.furnacePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
    }

    private void showPlacementItem(ItemStack stack) {
        this.setTemporaryMainHand(stack, false);
    }

    private void setTemporaryMainHand(ItemStack stack, boolean returnCurrentOnRestore) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryTool) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryTool = true;
            this.returnTemporaryMainHandOnRestore = returnCurrentOnRestore;
        } else if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        ItemStack held = stack.copy();
        held.setCount(Math.min(1, held.getCount()));
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, held);
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryTool) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.returnTemporaryMainHandOnRestore = false;
        this.cookTicks = 0;
    }

    private void finishPlacementMainHand() {
        if (!this.usingTemporaryTool) {
            return;
        }

        this.placingBlockAi.finishHeldPlacement(this.previousMainHand);
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.returnTemporaryMainHandOnRestore = false;
    }

    private void updateDetail(ServerLevel serverLevel) {
        if (this.furnacePos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        if (this.mode == Mode.RECOVER_TEMPORARY && serverLevel != null) {
            String breakingDetail = this.breakingBlockAi.detail();
            if (!breakingDetail.isBlank()) {
                this.playerNpc.setCurrentAiDetail(breakingDetail);
                return;
            }

            boolean inBreakRange = this.playerNpc.distanceToSqr(
                    this.furnacePos.getX() + 0.5D,
                    this.furnacePos.getY() + 0.5D,
                    this.furnacePos.getZ() + 0.5D
            ) <= RECOVER_FURNACE_BREAK_DISTANCE_SQR;
            this.playerNpc.setCurrentAiDetail(String.format(
                    java.util.Locale.ROOT,
                    "recovering furnace @ %d %d %d %s",
                    this.furnacePos.getX(),
                    this.furnacePos.getY(),
                    this.furnacePos.getZ(),
                    inBreakRange ? "recovering" : "walking"
            ));
            return;
        }

        String action = switch (this.mode) {
            case PLACE_HOME, PLACE_TEMPORARY -> "placing furnace";
            case RECOVER_TEMPORARY -> "recovering furnace";
            case INTERACT -> "using furnace";
        };
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "%s @ %d %d %d",
                action,
                this.furnacePos.getX(),
                this.furnacePos.getY(),
                this.furnacePos.getZ()
        ));
    }

    private void returnStack(ItemStack stack) {
        ItemStack remainder = InventoryUtils.addItemAndReturnRemainder(this.playerNpc, stack);
        if (!remainder.isEmpty()) {
            this.playerNpc.spawnAtLocation(remainder);
        }
    }

    private void resetPlan() {
        this.homeArea = null;
        this.furnacePos = null;
        this.furnaceStandPos = null;
        this.mode = Mode.INTERACT;
        this.previousMainHand = ItemStack.EMPTY;
        this.actionDelayTicks = 0;
        this.finished = false;
        this.acted = false;
        this.temporaryFurnace = false;
        this.usingTemporaryTool = false;
        this.returnTemporaryMainHandOnRestore = false;
        this.furnaceWorkReason = "unknown";
    }

    private enum Mode {
        PLACE_HOME,
        PLACE_TEMPORARY,
        INTERACT,
        RECOVER_TEMPORARY
    }
}
