package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.FurnaceAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.ai.ReturnPositionAi;
import com.pla.smart_npc.entity.ai.SneakingAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcFarmPlan.Plan;
import com.pla.smart_npc.util.PlayerNpcBaseUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public class MiningNightCampGoal extends Goal {
    public static final String AI_STATE = "ai.player_npc.mining_night_camp";
    public static final String BUILDING_BOOTSTRAP_AI_STATE = "ai.player_npc.building_night_camp";

    private static final int FURNACE_SCAN_RADIUS = 6;
    private static final int FURNACE_PLACEMENT_RADIUS = 3;
    private static final int CAMP_WALK_RADIUS = 5;
    private static final int CAMP_WALK_VERTICAL_RADIUS = 2;
    private static final int ACTION_DELAY_TICKS = 12;
    private static final int FURNACE_ACTION_COOLDOWN_TICKS = 20;
    private static final int FURNACE_FAIL_COOLDOWN_TICKS = 20 * 3;
    private static final int FURNACE_IDLE_SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_FURNACE_RECOVERY_TICKS = 20 * 10;
    private static final double FURNACE_USE_DISTANCE_SQR = 2.25D * 2.25D;
    private static final double FURNACE_STAND_REACHED_SQR = 1.25D * 1.25D;
    private static final int TORCH_CHECK_INTERVAL_TICKS = 20 * 5;
    private static final int TORCH_NEARBY_RADIUS = 6;
    private static final int TORCH_LOW_LIGHT_LEVEL = 7;
    private static final int TORCH_STAND_PATH_CHECKS = 8;
    private static final int TORCH_REPATH_TICKS = 20;
    private static final double TORCH_STAND_REACHED_SQR = 1.25D * 1.25D;
    private static final double TORCH_USE_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int FARM_FENCE_CLEAR_TICKS = 12;
    private static final int MAX_FARM_FENCE_CLEAR_ATTEMPTS = 6;
    private static final int FARM_FENCE_CLEAR_HORIZONTAL_RADIUS = 2;
    private static final int FARM_FENCE_CLEAR_VERTICAL_ABOVE = 2;
    private static final double FARM_FENCE_CLEAR_DISTANCE_SQR = 6.5D * 6.5D;
    private static final double FARM_CAMP_REACHED_SQR = 3.5D * 3.5D;
    private static final int MIN_ACTIVITY_TICKS = 20 * 4;
    private static final int RANDOM_ACTIVITY_TICKS = 20 * 6;
    private static final int MIN_STATIONARY_TICKS = 20 * 2;
    private static final int RANDOM_STATIONARY_TICKS = 20 * 4;
    private static final int MIN_LOOK_TICKS = 20;
    private static final int RANDOM_LOOK_TICKS = 20 * 3;
    private static final int WALK_REPATH_TICKS = 20;
    private static final int CAMP_WALK_CANDIDATE_CHECKS = 32;
    private static final int CAMP_WALK_PATH_CHECKS = 4;
    private static final float CAMP_WALK_PATH_NODE_MULTIPLIER = 0.10F;
    private static final int FARM_CAMP_SELECTION_PATH_CHECKS = 2;
    private static final float FARM_CAMP_SELECTION_PATH_NODE_MULTIPLIER = 0.03F;
    private static final float OVERLOADED_FARM_CAMP_SELECTION_PATH_NODE_MULTIPLIER = 0.01F;
    private static final int RESOURCELESS_NIGHT_ROAM_RADIUS = 8;
    private static final int RESOURCELESS_NIGHT_WALK_CANDIDATE_CHECKS = 128;
    private static final int RESOURCELESS_NIGHT_WALK_PATH_CHECKS = 4;
    private static final int RESOURCELESS_NIGHT_WALK_RETRY_TICKS = 10;
    private static final int RESOURCELESS_NIGHT_WALK_REPATH_TICKS = 20;
    private static final int RESOURCELESS_NIGHT_WALK_NO_PROGRESS_TICKS = 20 * 2;
    private static final int RESOURCELESS_NIGHT_WALK_MIN_DISTANCE_SQR = 2 * 2;
    private static final int CAN_USE_CHECK_INTERVAL_TICKS = 40;
    private static final int CONTINUE_CONTEXT_CHECK_INTERVAL_TICKS = 20;
    private static final String CAMP_FURNACE_X = "PlayerNpcNightCampFurnaceX";
    private static final String CAMP_FURNACE_Y = "PlayerNpcNightCampFurnaceY";
    private static final String CAMP_FURNACE_Z = "PlayerNpcNightCampFurnaceZ";
    private static final String CAMP_FURNACE_DIMENSION = "PlayerNpcNightCampFurnaceDimension";
    private static final String CAMP_FURNACE_OWNER = "PlayerNpcNightCampFurnaceOwner";

    private final PlayerNpcEntity playerNpc;
    private final FurnaceAi furnaceAi;
    private final PlacingBlockAi placingBlockAi;
    private final ToolAi farmFenceClearToolAi;
    private final ClearBlockAi farmFenceClearBlockAi;
    private final ReturnPositionAi returnPositionAi;
    private final SneakingAi sneakingAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(CAN_USE_CHECK_INTERVAL_TICKS);
    private final double speed;
    private BlockPos campCenter;
    private BlockPos furnacePos;
    private BlockPos furnaceStandPos;
    private BlockPos walkTarget;
    private BlockPos torchPos;
    private BlockPos torchStandPos;
    private BlockPos resourcelessWalkProgressPos;
    private FurnaceMode furnaceMode = FurnaceMode.NONE;
    private ActivityMode activityMode = ActivityMode.LOOK;
    private boolean relaxingForYsm;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int actionDelayTicks;
    private int furnaceCooldownTicks;
    private int torchCheckTicks;
    private int torchRepathTicks;
    private int farmFenceClearAttempts;
    private int activityTicks;
    private int stationaryTicks;
    private int lookTicks;
    private int repathTicks;
    private int furnaceRecoveryTicks;
    private int resourcelessWalkRetryTicks;
    private int resourcelessWalkNoProgressTicks;
    private int resourcelessWalkCandidateCursor;
    private boolean finished;
    private boolean placedTorch;
    private boolean farmingCamp;
    private boolean buildingBootstrapCamp;
    private boolean farmingBootstrapCamp;
    private boolean persistentBaseCamp;
    private int farmingBootstrapPlanCheckTicks;
    private boolean walkSneaking;
    private boolean usingTemporaryMainHand;
    private boolean returnTemporaryMainHandOnRestore;
    private boolean reusableCampAnchor;
    private boolean resourcelessNightWalkActive;
    private boolean farmCampArrivalComplete;
    private int nextContinueContextCheckTick;
    private boolean cachedContinueContext = true;

    public MiningNightCampGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.furnaceAi = new FurnaceAi(playerNpc);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.farmFenceClearToolAi = new ToolAi(playerNpc);
        this.farmFenceClearBlockAi = new ClearBlockAi(
                playerNpc,
                new BreakingBlockAi(playerNpc, this.farmFenceClearToolAi)
        );
        this.returnPositionAi = new ReturnPositionAi(playerNpc, Math.min(speed, 1.0D));
        this.sneakingAi = new SneakingAi(playerNpc);
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean shouldPauseMiningForNightCamp(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return false;
        }
        if (isBuildingBootstrapNightCamp(playerNpc, serverLevel)) {
            return true;
        }
        boolean farmingEligibleAtNight = isFarmingNightInterestEligible(playerNpc, serverLevel);
        boolean farmingHasPlan = farmingEligibleAtNight && FarmAi.getPlan(playerNpc, serverLevel).isPresent();
        if (isFarmingBootstrapNightContext(playerNpc, serverLevel) && !farmingHasPlan) {
            return true;
        }
        // Once a build area exists, Building interest owns night shelter behavior. A
        // previously placed temporary furnace is still recovered by canUse before this
        // normal-camp gate is consulted.
        if (playerNpc.hasInterest(PlayerNpcInterest.BUILDING)) {
            return false;
        }

        if (PlayerNpcBaseUtil.hasCampBaseJobs(playerNpc)) {
            return serverLevel.isNight()
                    && (PlayerNpcBaseUtil.getCampBase(playerNpc, serverLevel).isPresent()
                    || !PlayerNpcBaseUtil.hasStoredCampBase(playerNpc));
        }

        boolean miningJob = GatherStoneGoal.isMiningJobActive(playerNpc);
        boolean fishingJob = isFishingNightCampJob(playerNpc);
        boolean farmingJob = farmingEligibleAtNight && farmingHasPlan;
        return miningJob
                && (serverLevel.isNight() || serverLevel.isThundering())
                && !serverLevel.canSeeSky(playerNpc.blockPosition().above())
                || fishingJob && serverLevel.isNight()
                || farmingJob && serverLevel.isNight();
    }

    private static boolean isBuildingBootstrapNightCamp(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && serverLevel != null
                && serverLevel.isNight()
                && playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && PlayerNpcHomeUtil.getHome(playerNpc).isEmpty()
                && PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).isEmpty()
                && playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING);
    }

    private static boolean isFishingNightCampJob(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && playerNpc.hasInterest(PlayerNpcInterest.FISHING)
                && playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING);
    }

    private static boolean isFarmingNightCampJob(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && serverLevel != null
                && !playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && isFarmingNightInterestEligible(playerNpc, serverLevel)
                && FarmAi.getPlan(playerNpc, serverLevel).isPresent();
    }

    private static boolean isFarmingBootstrapNightCamp(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return isFarmingBootstrapNightContext(playerNpc, serverLevel)
                && FarmAi.getPlan(playerNpc, serverLevel).isEmpty();
    }

    private static boolean isFarmingBootstrapNightContext(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !isFarmingNightInterestEligible(playerNpc, serverLevel)) {
            return false;
        }
        // A mixed builder/farmer with a selected or built home uses normal home duty. If it has
        // neither a build anchor nor a farm plan, it may still wait out the night at a local camp.
        return !playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || PlayerNpcHomeUtil.getHome(playerNpc).isEmpty()
                && PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).isEmpty();
    }

    private static boolean isFarmingNightInterestEligible(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !serverLevel.isNight()
                || !playerNpc.hasInterest(PlayerNpcInterest.FARMING)) {
            return false;
        }

        // Farming is the persistent base whenever no builder home exists, even when another
        // assigned job owns today's work roll.
        return true;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.isInWaterOrBubble()
                || this.playerNpc.getUpwardEscapeTarget() != null) {
            return false;
        }
        // This goal is registered for every NPC. Its planning can inspect nearby furnace and farm
        // state, so a failed activation must not repeat every goal-selector pass.
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        // Camp owns MOVE/LOOK at a higher priority than home management. Give a farmer's
        // persistent base one nightly deposit pass before camping claims those controls.
        if (ManageHomeBaseGoal.hasPendingCampBaseNightlyDeposit(this.playerNpc, serverLevel)) {
            this.playerNpc.setIdleTraceDetail("night camp waiting for nightly base-chest deposit", 40);
            return false;
        }

        this.resetPlan();
        this.buildingBootstrapCamp = isBuildingBootstrapNightCamp(this.playerNpc, serverLevel);
        this.farmingBootstrapCamp = !this.buildingBootstrapCamp
                && isFarmingBootstrapNightCamp(this.playerNpc, serverLevel);
        this.farmingCamp = !this.buildingBootstrapCamp
                && !this.farmingBootstrapCamp
                && isFarmingNightCampJob(this.playerNpc, serverLevel);
        this.persistentBaseCamp = !this.buildingBootstrapCamp
                && !this.farmingBootstrapCamp
                && !this.farmingCamp
                && serverLevel.isNight()
                && PlayerNpcBaseUtil.hasCampBaseJobs(this.playerNpc)
                && (PlayerNpcBaseUtil.getCampBase(this.playerNpc, serverLevel).isPresent()
                || !PlayerNpcBaseUtil.hasStoredCampBase(this.playerNpc));
        if (this.farmingCamp) {
            this.campCenter = this.resolveFarmCampCenter(serverLevel);
        } else if (this.persistentBaseCamp) {
            this.campCenter = PlayerNpcBaseUtil.setCampBaseIfAbsent(
                    this.playerNpc,
                    serverLevel,
                    this.playerNpc.blockPosition()
            ).orElse(null);
        } else {
            this.campCenter = this.playerNpc.blockPosition().immutable();
        }
        if (this.buildingBootstrapCamp || this.farmingBootstrapCamp) {
            if (this.farmingBootstrapCamp) {
                this.playerNpc.setIdleTraceDetail("farming night camp: no valid farm plan, job="
                        + this.playerNpc.getSelectedDailyJobDisplayText(), 40);
            }
            return true;
        }
        this.adoptNearbyLegacyTemporaryFurnace(serverLevel);
        CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
        if (ownedFurnace != null) {
            if (!ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
                return false;
            }
            if (!this.isOwnedCampFurnace(ownedFurnace)) {
                this.clearCampFurnaceOwnership(ownedFurnace.pos());
            } else if (!shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)
                    || !this.canReuseOwnedFurnace(serverLevel, ownedFurnace)) {
                return this.planFurnaceRecovery(ownedFurnace);
            } else if (!this.farmingCamp
                    && !this.persistentBaseCamp
                    && GatherStoneGoal.isMiningJobActive(this.playerNpc)) {
                this.campCenter = ownedFurnace.pos().immutable();
                this.reusableCampAnchor = true;
            }
        }
        if (!shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            return false;
        }
        return (!this.farmingCamp && !this.persistentBaseCamp) || this.campCenter != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && !this.playerNpc.isInWaterOrBubble()
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.hasCachedContinueContext(serverLevel);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.finished = false;
        this.actionDelayTicks = 0;
        this.furnaceCooldownTicks = 0;
        this.torchCheckTicks = 0;
        this.activityMode = this.randomActivityMode(null);
        this.activityTicks = this.nextActivityTicks();
        this.stationaryTicks = this.nextStationaryTicks();
        this.lookTicks = 0;
        this.repathTicks = 0;
        this.farmingBootstrapPlanCheckTicks = 0;
        this.cachedContinueContext = true;
        this.nextContinueContextCheckTick = this.playerNpc.tickCount
                + CONTINUE_CONTEXT_CHECK_INTERVAL_TICKS;
        this.walkTarget = null;
        this.walkSneaking = false;
        this.playerNpc.getNavigation().stop();
        if ((this.farmingCamp || this.persistentBaseCamp) && this.campCenter != null) {
            this.returnPositionAi.start(this.campCenter);
        }
        this.farmCampArrivalComplete = this.farmingCamp && this.isWithinFarmCamp();
        this.playerNpc.setCurrentAiState(this.activeAiState());
        this.playerNpc.setCurrentAiDetail(this.buildingBootstrapCamp
                ? "camping before choosing build area"
                : this.farmingBootstrapCamp ? "camping before choosing farm area; job="
                        + this.playerNpc.getSelectedDailyJobDisplayText()
                : this.farmingCamp ? "returning to farm camp"
                : this.persistentBaseCamp ? "returning to permanent camp base"
                : "setting up night camp");
    }

    @Override
    public void tick() {
        this.relaxingForYsm = false;
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.finished = true;
            return;
        }

        this.playerNpc.setCurrentAiState(this.activeAiState());
        if (this.buildingBootstrapCamp || this.farmingBootstrapCamp) {
            if (this.buildingBootstrapCamp
                    && !isBuildingBootstrapNightCamp(this.playerNpc, serverLevel)) {
                this.finished = true;
                return;
            }
            if (this.farmingBootstrapCamp && this.farmingBootstrapPlanCheckTicks-- <= 0) {
                this.farmingBootstrapPlanCheckTicks = 20;
                if (!isFarmingBootstrapNightCamp(this.playerNpc, serverLevel)) {
                    this.finished = true;
                    return;
                }
            }
            this.tickCampActivity(serverLevel);
            return;
        }
        if (this.furnaceMode == FurnaceMode.RECOVER) {
            this.tickRecoverFurnace(serverLevel);
            return;
        }
        if (this.farmingCamp && !this.farmCampArrivalComplete && this.isWithinFarmCamp()) {
            // Farm camp walking intentionally ranges around the whole exterior perimeter,
            // which can extend beyond the tighter arrival radius. Once the initial return
            // reaches camp, do not let that radius preempt the goal's own WALK activity and
            // pause its activity timer while sending the NPC back and forth.
            this.farmCampArrivalComplete = true;
            this.returnPositionAi.stop();
        }
        boolean atPersistentCamp = this.farmingCamp && this.isWithinFarmCamp()
                || this.persistentBaseCamp && this.isWithinPersistentBaseCamp();
        if (atPersistentCamp
                && ManageHomeBaseGoal.hasPendingCampBaseNightlyDeposit(this.playerNpc, serverLevel)) {
            // A worker may have been away from base when night began. End camp once it has
            // escorted the worker back, allowing the same one-pass handoff used at dusk.
            this.playerNpc.setCurrentAiDetail("ready for nightly base-chest deposit");
            this.finished = true;
            return;
        }
        if (!shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
            if (ownedFurnace == null || !ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
                this.finished = true;
                return;
            }
            if (!this.isOwnedCampFurnace(ownedFurnace)) {
                this.clearCampFurnaceOwnership(ownedFurnace.pos());
                this.finished = true;
                return;
            }
            this.planFurnaceRecovery(ownedFurnace);
            this.tickRecoverFurnace(serverLevel);
            return;
        }
        boolean activeFarmFenceLight = this.hasActiveFarmFenceLightAction();
        if (this.farmingCamp && !this.farmCampArrivalComplete && !activeFarmFenceLight) {
            this.returnPositionAi.tickFarmCampReturn(
                    serverLevel,
                    this.campCenter,
                    pos -> PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                            || FarmAi.isProtectedFarmBlock(this.playerNpc, pos),
                    "returning to farm camp",
                    "clearing farm camp route"
            );
            this.playerNpc.setCurrentAiDetail(this.returnPositionAi.detail("returning to farm camp"));
            return;
        }
        if (this.persistentBaseCamp && !this.isWithinPersistentBaseCamp()) {
            this.returnPositionAi.tick(
                    serverLevel,
                    this.campCenter,
                    pos -> PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                            || FarmAi.isProtectedFarmBlock(this.playerNpc, pos),
                    "returning to permanent camp base",
                    "clearing permanent camp route",
                    true
            );
            this.playerNpc.setCurrentAiDetail(this.returnPositionAi.detail("returning to permanent camp base"));
            return;
        }
        // A farm fence stand may lie just across the camp boundary. Once lighting owns
        // movement, do not let the outside-camp return branch replace its path mid-step.
        if (activeFarmFenceLight) {
            this.tickTorchPlacement(serverLevel);
            return;
        }
        if (this.tickFurnaceWork(serverLevel)) {
            return;
        }
        if (this.tickTorchPlacement(serverLevel)) {
            return;
        }

        if (this.shouldUseResourcelessMiningNightWalk()) {
            this.tickResourcelessMiningNightWalk(serverLevel);
            return;
        }
        this.stopResourcelessMiningNightWalk();
        this.tickCampActivity(serverLevel);
    }

    private boolean hasCachedContinueContext(ServerLevel serverLevel) {
        if (this.playerNpc.tickCount < this.nextContinueContextCheckTick) {
            return this.cachedContinueContext;
        }
        this.nextContinueContextCheckTick = this.playerNpc.tickCount
                + CONTINUE_CONTEXT_CHECK_INTERVAL_TICKS;
        this.cachedContinueContext = this.buildingBootstrapCamp
                ? isBuildingBootstrapNightCamp(this.playerNpc, serverLevel)
                : this.farmingBootstrapCamp
                        ? isFarmingBootstrapNightContext(this.playerNpc, serverLevel)
                        : this.furnaceMode == FurnaceMode.RECOVER
                        || shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)
                        || this.hasOwnedCampFurnaceReference();
        return this.cachedContinueContext;
    }

    @Override
    public void stop() {
        this.relaxingForYsm = false;
        this.stopResourcelessMiningNightWalk();
        this.stopFarmFenceClear();
        this.restorePreviousMainHand();
        this.sneakingAi.stopSneaking();
        this.returnPositionAi.stop();
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
    }

    private boolean tickFurnaceWork(ServerLevel serverLevel) {
        if (this.furnaceCooldownTicks > 0) {
            this.furnaceCooldownTicks--;
        }

        if (this.furnaceMode == FurnaceMode.PLACE) {
            this.tickPlaceFurnace(serverLevel);
            return true;
        }
        if (this.furnaceMode == FurnaceMode.INTERACT) {
            this.tickUseFurnace(serverLevel);
            return true;
        }
        if (this.furnaceCooldownTicks > 0) {
            return false;
        }
        // With no actionable furnace, the old loop scanned the complete 13x5x13 local volume on
        // every goal tick. One-second polling is still prompt for smelting while removing the
        // repeated 845-block scan from ordinary camp walking/looking ticks.
        this.furnaceCooldownTicks = FURNACE_IDLE_SCAN_INTERVAL_TICKS;

        BlockPos workFurnace = this.findWorkFurnace(serverLevel);
        if (workFurnace != null && this.planFurnaceInteraction(serverLevel, workFurnace)) {
            this.tickUseFurnace(serverLevel);
            return true;
        }

        CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
        if (ownedFurnace != null
                && ownedFurnace.level().hasChunkAt(ownedFurnace.pos())
                && this.isOwnedCampFurnace(ownedFurnace)) {
            return false;
        }
        BlockPos trackedTemporary = this.getTemporaryFurnacePos();
        if (trackedTemporary != null) {
            return false;
        }

        if (this.furnaceAi.shouldPlaceFurnaceForWork(serverLevel)) {
            BlockPos placement = this.findFurnacePlacement(serverLevel);
            if (placement != null && this.planFurnacePlacement(serverLevel, placement)) {
                this.tickPlaceFurnace(serverLevel);
                return true;
            }
        }
        return false;
    }

    private void tickUseFurnace(ServerLevel serverLevel) {
        if (!serverLevel.getBlockState(this.furnacePos).is(Blocks.FURNACE)
                || !(serverLevel.getBlockEntity(this.furnacePos) instanceof FurnaceBlockEntity furnace)) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        if (!this.ensureFurnaceStand(serverLevel)) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        this.lookAtFurnace();
        if (!this.isAtFurnaceStand()) {
            this.playerNpc.setCurrentAiDetail(this.detail("walking to mining camp furnace", this.furnacePos));
            if (!this.moveToFurnaceStand()) {
                this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail(this.detail("using mining camp furnace", this.furnacePos));
            return;
        }
        this.actionDelayTicks = 0;

        boolean moved = this.furnaceAi.takeOutput(serverLevel, this.furnacePos, furnace)
                || this.furnaceAi.fillFurnace(serverLevel, this.furnacePos, furnace);
        this.clearActiveFurnaceAction(moved ? FURNACE_ACTION_COOLDOWN_TICKS : FURNACE_FAIL_COOLDOWN_TICKS);
    }

    private void tickPlaceFurnace(ServerLevel serverLevel) {
        if (!this.ensureFurnaceStand(serverLevel)) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        this.lookAtFurnace();
        if (!this.isAtFurnaceStand()) {
            this.playerNpc.setCurrentAiDetail(this.detail("walking to furnace placement", this.furnacePos));
            if (!this.moveToFurnaceStand()) {
                this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail(this.detail("preparing furnace", this.furnacePos));
            return;
        }
        this.actionDelayTicks = 0;

        ItemStack furnace = this.takeOrCraftFurnace();
        if (furnace.isEmpty()) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }
        if (!this.canPlaceFurnaceAt(serverLevel, this.furnacePos)) {
            this.returnStack(furnace);
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        this.showPlacementItem(furnace);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.furnacePos, Blocks.FURNACE.defaultBlockState())) {
            this.returnStack(furnace);
            this.restorePreviousMainHand();
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        if (!this.saveTemporaryFurnace(serverLevel, this.furnacePos)) {
            boolean removed = serverLevel.removeBlock(this.furnacePos, false);
            this.finishPlacementMainHand();
            if (removed) {
                this.returnStack(furnace);
            }
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        if (!this.farmingCamp
                && !this.persistentBaseCamp
                && GatherStoneGoal.isMiningJobActive(this.playerNpc)) {
            this.campCenter = this.furnacePos.immutable();
            this.reusableCampAnchor = true;
        }
        this.finishPlacementMainHand();
        this.furnaceMode = FurnaceMode.INTERACT;
        this.furnaceCooldownTicks = 0;
        this.furnaceStandPos = this.findFurnaceStand(serverLevel, this.furnacePos);
        if (this.furnaceStandPos == null) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
        }
    }

    private boolean tickTorchPlacement(ServerLevel serverLevel) {
        if (this.placedTorch && !this.farmingCamp) {
            return false;
        }
        if (this.farmFenceClearBlockAi.isRunning()) {
            this.tickFarmFenceClear(serverLevel);
            return true;
        }
        if (this.torchPos == null) {
            if (this.torchCheckTicks > 0) {
                this.torchCheckTicks--;
                return false;
            }
            this.torchCheckTicks = TORCH_CHECK_INTERVAL_TICKS;
            this.torchPos = this.findTorchPlacement(serverLevel);
            this.torchStandPos = this.findTorchStand(serverLevel, this.torchPos);
            this.actionDelayTicks = 0;
            this.torchRepathTicks = 0;
            this.farmFenceClearAttempts = 0;
            if (this.torchPos == null || this.torchStandPos == null) {
                this.clearTorchAction();
                return false;
            }
        }

        if (!this.canPlaceTorchAt(serverLevel, this.torchPos)) {
            if (this.tryStartFarmFenceClear(serverLevel)) {
                return true;
            }
            this.clearTorchAction();
            return false;
        }
        this.lookAt(this.torchPos);
        if (!this.isAtTorchStand()) {
            this.playerNpc.setCurrentAiDetail(this.detail(
                    this.farmingCamp ? "walking to farm fence light" : "walking to camp light",
                    this.torchPos
            ));
            boolean navigationActive = !this.playerNpc.getNavigation().isDone()
                    && !this.playerNpc.getNavigation().isStuck();
            if (navigationActive && this.torchRepathTicks-- > 0) {
                return true;
            }
            boolean routeFailure = this.playerNpc.getNavigation().isStuck()
                    || !PathNavigationAi.canStandAt(serverLevel, this.torchStandPos);
            Path path = routeFailure ? null : this.playerNpc.getNavigation().createPath(this.torchStandPos, 0);
            boolean usablePath = path != null
                    && path.canReach()
                    && path.getEndNode() != null
                    && path.getEndNode().asBlockPos().equals(this.torchStandPos);
            boolean moved = usablePath && this.playerNpc.getNavigation().moveTo(path, this.speed);
            if (!moved && this.tryStartFarmFenceClear(serverLevel)) {
                return true;
            }
            if (!moved) {
                this.clearTorchAction();
                return false;
            }
            this.torchRepathTicks = TORCH_REPATH_TICKS;
            return true;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail(this.detail(
                    this.farmingCamp ? "preparing farm fence light" : "preparing camp light",
                    this.torchPos
            ));
            return true;
        }
        this.actionDelayTicks = 0;
        ItemStack torch = this.takeOrCraftTorch();
        if (torch.isEmpty()) {
            this.clearTorchAction();
            return false;
        }

        this.showPlacementItem(torch);
        BlockPos placedPos = this.torchPos.immutable();
        if (!this.placingBlockAi.placeBlock(serverLevel, placedPos, Blocks.TORCH.defaultBlockState())) {
            this.returnStack(torch);
            this.restorePreviousMainHand();
            this.clearTorchAction();
            return false;
        }

        this.finishPlacementMainHand();
        this.placedTorch = !this.farmingCamp;
        this.playerNpc.setCurrentAiDetail(this.detail(
                this.farmingCamp ? "placing farm fence torch" : "placing night camp torch",
                placedPos
        ));
        this.clearTorchAction();
        return true;
    }

    private void tickCampActivity(ServerLevel serverLevel) {
        if (this.activityTicks-- <= 0) {
            this.switchCampActivity();
        }

        if (this.activityMode == ActivityMode.WALK) {
            this.tickWalkCamp(serverLevel);
        } else if (this.activityMode == ActivityMode.SNEAK) {
            this.playerNpc.getNavigation().stop();
            this.sneakingAi.setSneaking(true);
            this.playerNpc.setCurrentAiDetail("sneaking around " + this.campActivityContext());
            this.lookAroundCamp();
        } else {
            this.relaxingForYsm = true;
            this.playerNpc.getNavigation().stop();
            this.sneakingAi.setSneaking(false);
            this.playerNpc.setCurrentAiDetail("watching " + this.campActivityContext());
            this.lookAroundCamp();
        }
    }

    public boolean isRelaxingForYsm() {
        return this.relaxingForYsm;
    }

    private boolean shouldUseResourcelessMiningNightWalk() {
        return !this.buildingBootstrapCamp
                && !this.farmingCamp
                && !this.persistentBaseCamp
                && GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && ResourceAi.countLogs(this.playerNpc) <= 0
                && ResourceAi.countStone(this.playerNpc) <= 0;
    }

    private void tickResourcelessMiningNightWalk(ServerLevel serverLevel) {
        if (!this.resourcelessNightWalkActive) {
            this.resourcelessNightWalkActive = true;
            this.sneakingAi.stopSneaking();
            this.playerNpc.getNavigation().stop();
            this.walkTarget = null;
            this.resourcelessWalkRetryTicks = 0;
            this.resourcelessWalkCandidateCursor = this.playerNpc.getRandom().nextInt(997);
            this.repathTicks = 0;
            this.resetResourcelessWalkProgress();
        }

        this.sneakingAi.setSneaking(false);
        this.lookAroundCamp();
        if (this.walkTarget != null && this.hasReachedWalkTarget()) {
            this.playerNpc.getNavigation().stop();
            this.walkTarget = null;
            this.resourcelessWalkRetryTicks = 0;
            this.repathTicks = 0;
            this.resetResourcelessWalkProgress();
        }

        if (this.walkTarget != null) {
            BlockPos feet = this.playerNpc.blockPosition();
            if (this.resourcelessWalkProgressPos == null
                    || !this.resourcelessWalkProgressPos.equals(feet)) {
                this.resourcelessWalkProgressPos = feet.immutable();
                this.resourcelessWalkNoProgressTicks = 0;
            } else {
                this.resourcelessWalkNoProgressTicks += this.playerNpc.getNavigation().isDone() ? 4 : 1;
            }
            if (this.playerNpc.getNavigation().isStuck()
                    || this.resourcelessWalkNoProgressTicks >= RESOURCELESS_NIGHT_WALK_NO_PROGRESS_TICKS) {
                this.abandonResourcelessWalkTarget();
            }
        }

        if (this.walkTarget == null) {
            if (this.resourcelessWalkRetryTicks-- > 0) {
                this.playerNpc.setCurrentAiDetail(this.resourcelessNightWalkDetail("finding a safe nearby route", null));
                return;
            }
            NightWalkPlan plan = this.findResourcelessNightWalkPlan(serverLevel);
            if (plan == null || !this.playerNpc.getNavigation().moveTo(plan.path(), this.speed)) {
                this.abandonResourcelessWalkTarget();
                this.playerNpc.setCurrentAiDetail(this.resourcelessNightWalkDetail("no safe nearby route; retrying", null));
                return;
            }
            this.walkTarget = plan.target();
            this.repathTicks = RESOURCELESS_NIGHT_WALK_REPATH_TICKS;
            this.resetResourcelessWalkProgress();
        }

        this.playerNpc.setCurrentAiDetail(this.resourcelessNightWalkDetail("walking", this.walkTarget));
        if (this.repathTicks-- > 0 && !this.playerNpc.getNavigation().isDone()) {
            return;
        }

        this.repathTicks = RESOURCELESS_NIGHT_WALK_REPATH_TICKS;
        Path path = this.createBoundedNightWalkPath(serverLevel, this.walkTarget);
        if (path == null || !this.playerNpc.getNavigation().moveTo(path, this.speed)) {
            this.abandonResourcelessWalkTarget();
        }
    }

    private NightWalkPlan findResourcelessNightWalkPlan(ServerLevel serverLevel) {
        BlockPos anchor = this.campCenter != null
                ? this.campCenter
                : this.playerNpc.blockPosition().immutable();
        BlockPos feet = this.playerNpc.blockPosition();
        int radius = this.reusableCampAnchor ? CAMP_WALK_RADIUS : RESOURCELESS_NIGHT_ROAM_RADIUS;
        int radiusSqr = radius * radius;
        boolean keepUnderground = !serverLevel.canSeeSky(anchor.above());
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -CAMP_WALK_VERTICAL_RADIUS; dy <= CAMP_WALK_VERTICAL_RADIUS; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radiusSqr) {
                        continue;
                    }
                    BlockPos candidate = anchor.offset(dx, dy, dz);
                    if (candidate.distSqr(feet) < RESOURCELESS_NIGHT_WALK_MIN_DISTANCE_SQR) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        int candidateCount = candidates.size();
        int start = Math.floorMod(this.resourcelessWalkCandidateCursor, candidateCount);
        int candidateChecks = Math.min(RESOURCELESS_NIGHT_WALK_CANDIDATE_CHECKS, candidateCount);
        int pathChecks = 0;
        for (int checked = 0; checked < candidateChecks; checked++) {
            int index = Math.floorMod(start + checked * 37, candidateCount);
            BlockPos candidate = candidates.get(index);
            if (!this.isSafeNightWalkStand(serverLevel, candidate)
                    || keepUnderground && serverLevel.canSeeSky(candidate.above())) {
                continue;
            }
            if (pathChecks++ >= RESOURCELESS_NIGHT_WALK_PATH_CHECKS) {
                break;
            }
            Path path = this.createBoundedNightWalkPath(serverLevel, candidate);
            if (path != null) {
                this.resourcelessWalkCandidateCursor = index + 37;
                return new NightWalkPlan(candidate, path);
            }
        }
        this.resourcelessWalkCandidateCursor = start + candidateChecks * 37;
        return null;
    }

    private Path createBoundedNightWalkPath(ServerLevel serverLevel, BlockPos target) {
        if (target == null || !this.isSafeNightWalkStand(serverLevel, target)) {
            return null;
        }
        BlockPos anchor = this.campCenter != null ? this.campCenter : this.playerNpc.blockPosition();
        int radius = this.reusableCampAnchor ? CAMP_WALK_RADIUS : RESOURCELESS_NIGHT_ROAM_RADIUS;
        Path path = PathNavigationAi.createBoundedPath(
                this.playerNpc,
                target,
                CAMP_WALK_PATH_NODE_MULTIPLIER
        );
        if (path == null
                || !path.canReach()
                || path.getEndNode() == null
                || !path.getEndNode().asBlockPos().equals(target)) {
            return null;
        }

        int pathRadius = radius + 1;
        int pathRadiusSqr = pathRadius * pathRadius;
        for (int i = 0; i < path.getNodeCount(); i++) {
            BlockPos node = path.getNode(i).asBlockPos();
            int dx = node.getX() - anchor.getX();
            int dz = node.getZ() - anchor.getZ();
            if (dx * dx + dz * dz > pathRadiusSqr
                    || Math.abs(node.getY() - anchor.getY()) > CAMP_WALK_VERTICAL_RADIUS + 2) {
                return null;
            }
        }
        return path;
    }

    private boolean isSafeNightWalkStand(ServerLevel serverLevel, BlockPos pos) {
        if (!PathNavigationAi.canStandAt(serverLevel, pos)) {
            return false;
        }
        BlockState feet = serverLevel.getBlockState(pos);
        BlockState ground = serverLevel.getBlockState(pos.below());
        return !feet.is(Blocks.FIRE)
                && !feet.is(Blocks.SOUL_FIRE)
                && !feet.is(Blocks.POWDER_SNOW)
                && !ground.is(Blocks.MAGMA_BLOCK)
                && !ground.is(Blocks.CAMPFIRE)
                && !ground.is(Blocks.SOUL_CAMPFIRE);
    }

    private void abandonResourcelessWalkTarget() {
        this.playerNpc.getNavigation().stop();
        this.walkTarget = null;
        this.repathTicks = 0;
        this.resourcelessWalkRetryTicks = RESOURCELESS_NIGHT_WALK_RETRY_TICKS;
        this.resetResourcelessWalkProgress();
    }

    private void stopResourcelessMiningNightWalk() {
        if (!this.resourcelessNightWalkActive) {
            return;
        }
        this.playerNpc.getNavigation().stop();
        this.walkTarget = null;
        this.repathTicks = 0;
        this.resourcelessWalkRetryTicks = 0;
        this.resourcelessWalkCandidateCursor = 0;
        this.resourcelessNightWalkActive = false;
        this.resetResourcelessWalkProgress();
    }

    private void resetResourcelessWalkProgress() {
        this.resourcelessWalkProgressPos = null;
        this.resourcelessWalkNoProgressTicks = 0;
    }

    private String resourcelessNightWalkDetail(String action, BlockPos target) {
        String context = this.reusableCampAnchor
                ? "temporary mining camp"
                : "current night position";
        String detail = action + " around " + context;
        return target == null ? detail : this.detail(detail, target);
    }

    private void tickWalkCamp(ServerLevel serverLevel) {
        this.sneakingAi.setSneaking(this.walkSneaking);
        this.lookAroundCamp();

        if (this.walkTarget == null || this.hasReachedWalkTarget()) {
            this.walkTarget = null;
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setCurrentAiDetail("walking around " + this.campActivityContext());
            if (this.stationaryTicks-- > 0) {
                return;
            }

            this.stationaryTicks = this.nextStationaryTicks();
            this.walkTarget = this.findCampWalkTarget(serverLevel);
            this.repathTicks = 0;
            this.walkSneaking = this.playerNpc.getRandom().nextFloat() < 0.35F;
            if (this.walkTarget == null) {
                return;
            }
        }

        this.playerNpc.setCurrentAiDetail(this.detail(
                "walking around " + this.campActivityContext(),
                this.walkTarget
        ));
        if (this.repathTicks-- > 0 && !this.playerNpc.getNavigation().isDone()) {
            return;
        }
        this.repathTicks = WALK_REPATH_TICKS;
        Path path = PathNavigationAi.createBoundedPath(
                this.playerNpc,
                this.walkTarget,
                CAMP_WALK_PATH_NODE_MULTIPLIER
        );
        if (path == null || !path.canReach()) {
            this.walkTarget = null;
            return;
        }
        this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private BlockPos findWorkFurnace(ServerLevel serverLevel) {
        CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
        if (ownedFurnace != null
                && ownedFurnace.level() == serverLevel
                && ownedFurnace.level().hasChunkAt(ownedFurnace.pos())
                && this.isOwnedCampFurnace(ownedFurnace)
                && this.furnaceAi.hasFurnaceWork(serverLevel, ownedFurnace.pos())) {
            return ownedFurnace.pos();
        }

        BlockPos temporary = this.getTemporaryFurnacePos();
        if (temporary != null) {
            if (serverLevel.getBlockState(temporary).is(Blocks.FURNACE)) {
                if (this.furnaceAi.hasFurnaceWork(serverLevel, temporary)) {
                    return temporary.immutable();
                }
            } else {
                this.clearTemporaryFurnace();
            }
        }

        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-FURNACE_SCAN_RADIUS, -2, -FURNACE_SCAN_RADIUS),
                center.offset(FURNACE_SCAN_RADIUS, 2, FURNACE_SCAN_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (serverLevel.getBlockState(immutable).is(Blocks.FURNACE)
                    && this.furnaceAi.hasFurnaceWork(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean planFurnaceInteraction(ServerLevel serverLevel, BlockPos pos) {
        BlockPos stand = this.findFurnaceStand(serverLevel, pos);
        if (stand == null) {
            return false;
        }
        this.furnaceMode = FurnaceMode.INTERACT;
        this.furnacePos = pos.immutable();
        this.furnaceStandPos = stand;
        this.actionDelayTicks = 0;
        return true;
    }

    private boolean planFurnacePlacement(ServerLevel serverLevel, BlockPos pos) {
        BlockPos stand = this.findFurnaceStand(serverLevel, pos);
        if (stand == null) {
            return false;
        }
        this.furnaceMode = FurnaceMode.PLACE;
        this.furnacePos = pos.immutable();
        this.furnaceStandPos = stand;
        this.actionDelayTicks = 0;
        return true;
    }

    private boolean planFurnaceRecovery(CampFurnaceRef ownedFurnace) {
        if (ownedFurnace == null || !ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
            return false;
        }
        this.furnaceMode = FurnaceMode.RECOVER;
        this.furnacePos = ownedFurnace.pos();
        this.furnaceStandPos = ownedFurnace.level() == this.playerNpc.level()
                ? this.findFurnaceStand(ownedFurnace.level(), ownedFurnace.pos())
                : null;
        this.actionDelayTicks = 0;
        this.furnaceRecoveryTicks = 0;
        return true;
    }

    private void tickRecoverFurnace(ServerLevel currentLevel) {
        CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
        if (ownedFurnace == null) {
            this.finished = true;
            return;
        }
        if (!ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
            this.finished = true;
            return;
        }
        if (!this.isOwnedCampFurnace(ownedFurnace)) {
            this.clearCampFurnaceOwnership(ownedFurnace.pos());
            this.finished = true;
            return;
        }

        this.furnacePos = ownedFurnace.pos();
        this.furnaceRecoveryTicks++;
        if (ownedFurnace.level() != currentLevel) {
            this.finished = this.reclaimOwnedCampFurnace(ownedFurnace);
            return;
        }

        this.lookAtFurnace();
        if (!this.ensureFurnaceStand(currentLevel) || !this.isAtFurnaceStand()) {
            this.playerNpc.setCurrentAiDetail(this.detail("recovering temporary camp furnace", this.furnacePos));
            if (this.furnaceStandPos != null) {
                this.moveToFurnaceStand();
            }
            if (this.furnaceRecoveryTicks >= MAX_FURNACE_RECOVERY_TICKS) {
                this.finished = this.reclaimOwnedCampFurnace(ownedFurnace);
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail(this.detail("packing temporary camp furnace", this.furnacePos));
            return;
        }
        this.finished = this.reclaimOwnedCampFurnace(ownedFurnace);
    }

    private BlockPos findFurnacePlacement(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(direction));
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -FURNACE_PLACEMENT_RADIUS; dx <= FURNACE_PLACEMENT_RADIUS; dx++) {
                for (int dz = -FURNACE_PLACEMENT_RADIUS; dz <= FURNACE_PLACEMENT_RADIUS; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= 1) {
                        continue;
                    }
                    candidates.add(center.offset(dx, dy, dz));
                }
            }
        }

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
        BlockState furnaceState = Blocks.FURNACE.defaultBlockState();
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !this.isInsideOwnedFarmFurnaceExclusion(pos)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below())
                && this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, furnaceState);
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
            if (!PathNavigationAi.canStandAt(serverLevel, immutable)
                    || this.distanceToFurnaceSqr(immutable, pos) > FURNACE_USE_DISTANCE_SQR) {
                continue;
            }
            if (immutable.equals(center)) {
                return immutable;
            }
            Path path = this.playerNpc.getNavigation().createPath(immutable, 0);
            if (path != null
                    && path.canReach()
                    && path.getEndNode() != null
                    && path.getEndNode().asBlockPos().equals(immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean ensureFurnaceStand(ServerLevel serverLevel) {
        if (this.furnaceStandPos != null && PathNavigationAi.canStandAt(serverLevel, this.furnaceStandPos)) {
            return true;
        }
        this.furnaceStandPos = this.findFurnaceStand(serverLevel, this.furnacePos);
        return this.furnaceStandPos != null;
    }

    private boolean isAtFurnaceStand() {
        return this.furnaceStandPos != null
                && this.playerNpc.distanceToSqr(
                this.furnaceStandPos.getX() + 0.5D,
                this.furnaceStandPos.getY(),
                this.furnaceStandPos.getZ() + 0.5D
        ) <= FURNACE_STAND_REACHED_SQR
                && this.distanceToFurnaceSqr(this.playerNpc.blockPosition(), this.furnacePos) <= FURNACE_USE_DISTANCE_SQR + 1.0D;
    }

    private boolean moveToFurnaceStand() {
        if (this.furnaceStandPos == null) {
            return false;
        }
        Path path = this.playerNpc.getNavigation().createPath(this.furnaceStandPos, 0);
        return path != null
                && path.canReach()
                && path.getEndNode() != null
                && path.getEndNode().asBlockPos().equals(this.furnaceStandPos)
                && this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private double distanceToFurnaceSqr(BlockPos standPos, BlockPos pos) {
        if (standPos == null || pos == null) {
            return Double.MAX_VALUE;
        }
        double dx = standPos.getX() + 0.5D - (pos.getX() + 0.5D);
        double dy = standPos.getY() + 0.5D - (pos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (pos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private BlockPos findTorchPlacement(ServerLevel serverLevel) {
        if (this.farmingCamp) {
            BlockPos pending = FarmAi.findPendingFarmTorchPlacement(serverLevel, this.playerNpc).orElse(null);
            if (pending != null) {
                return pending;
            }
            Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
            if (plan == null) {
                return null;
            }
            // A leaf/log in the placement cell is itself a recoverable obstruction. Keep
            // this fallback limited to planned torch cells over this NPC's existing fence.
            return FarmAi.farmTorchTargets(plan).stream()
                    .filter(pos -> this.isOwnedFarmFenceLightTarget(serverLevel, plan, pos))
                    .filter(pos -> serverLevel.getBrightness(LightLayer.BLOCK, pos) <= TORCH_LOW_LIGHT_LEVEL)
                    .filter(pos -> this.isSafeFarmFenceClearTarget(serverLevel, plan, pos, pos, null))
                    .findFirst()
                    .map(BlockPos::immutable)
                    .orElse(null);
        }
        BlockPos center = this.playerNpc.blockPosition();
        if (serverLevel.getBrightness(LightLayer.BLOCK, center) > TORCH_LOW_LIGHT_LEVEL || this.hasNearbyTorch(serverLevel, center)) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(direction));
            candidates.add(center.relative(direction).above());
        }
        candidates.add(center);

        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canPlaceTorchAt(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canPlaceTorchAt(ServerLevel serverLevel, BlockPos pos) {
        BlockState torchState = Blocks.TORCH.defaultBlockState();
        boolean ownedFarmFenceTarget = !this.farmingCamp || FarmAi.getPlan(this.playerNpc, serverLevel)
                .map(plan -> FarmAi.farmTorchTargets(plan).contains(pos)
                        && serverLevel.getBlockState(pos.below()).getBlock() instanceof net.minecraft.world.level.block.FenceBlock
                        && !pos.below().equals(plan.gatePos()))
                .orElse(false);
        return ownedFarmFenceTarget
                && serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getFluidState(pos).isEmpty()
                && torchState.canSurvive(serverLevel, pos);
    }

    private BlockPos findTorchStand(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null) {
            return null;
        }
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(pos.relative(direction).below());
            candidates.add(pos.relative(direction));
            candidates.add(pos.relative(direction, 2).below());
        }
        BlockPos feet = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(feet::distSqr));
        Plan farmPlan = this.farmingCamp ? FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null) : null;
        BlockPos blockedFarmStand = null;
        int pathChecks = 0;
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.distanceToTorchSqr(immutable, pos) > TORCH_USE_DISTANCE_SQR
                    || farmPlan != null && !this.isFarmFenceTorchStandGeometry(serverLevel, farmPlan, immutable)) {
                continue;
            }
            boolean canStand = PathNavigationAi.canStandAt(serverLevel, immutable);
            if (canStand && immutable.equals(feet)) {
                return immutable;
            }
            if (canStand && pathChecks++ < TORCH_STAND_PATH_CHECKS) {
                Path path = this.playerNpc.getNavigation().createPath(immutable, 0);
                if (path != null
                        && path.canReach()
                        && path.getEndNode() != null
                        && path.getEndNode().asBlockPos().equals(immutable)) {
                    return immutable;
                }
            }
            if (farmPlan != null
                    && blockedFarmStand == null
                    && this.canPrepareFarmFenceTorchStand(serverLevel, farmPlan, immutable)) {
                blockedFarmStand = immutable;
            }
        }
        return blockedFarmStand;
    }

    private boolean isAtTorchStand() {
        return this.torchStandPos != null
                && this.playerNpc.blockPosition().getY() == this.torchStandPos.getY()
                && this.playerNpc.distanceToSqr(
                this.torchStandPos.getX() + 0.5D,
                this.torchStandPos.getY(),
                this.torchStandPos.getZ() + 0.5D
        ) <= TORCH_STAND_REACHED_SQR;
    }

    private double distanceToTorchSqr(BlockPos stand, BlockPos torch) {
        double dx = stand.getX() + 0.5D - (torch.getX() + 0.5D);
        double dy = stand.getY() + 1.62D - (torch.getY() + 0.5D);
        double dz = stand.getZ() + 0.5D - (torch.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private void clearTorchAction() {
        this.stopFarmFenceClear();
        this.torchPos = null;
        this.torchStandPos = null;
        this.actionDelayTicks = 0;
        this.torchRepathTicks = 0;
        this.farmFenceClearAttempts = 0;
    }

    private boolean hasActiveFarmFenceLightAction() {
        return this.farmingCamp
                && (this.torchPos != null || this.farmFenceClearBlockAi.isRunning());
    }

    private boolean tryStartFarmFenceClear(ServerLevel serverLevel) {
        if (!this.farmingCamp
                || this.torchPos == null
                || this.torchStandPos == null
                || this.farmFenceClearBlockAi.isRunning()
                || this.farmFenceClearAttempts >= MAX_FARM_FENCE_CLEAR_ATTEMPTS) {
            return false;
        }
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (plan == null || !this.isOwnedFarmFenceLightTarget(serverLevel, plan, this.torchPos)) {
            return false;
        }

        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(
                this.playerNpc.blockPosition(),
                this.torchStandPos,
                this.torchPos
        ));
        candidates.add(this.torchPos);
        candidates.add(this.torchStandPos);
        candidates.add(this.torchStandPos.above());
        candidates.removeIf(pos -> !this.isSafeFarmFenceClearTarget(
                serverLevel,
                plan,
                pos,
                this.torchPos,
                this.torchStandPos
        ));
        BlockPos clearTarget = ClearBlockAi.findNearestClearable(
                serverLevel,
                this.playerNpc.blockPosition(),
                candidates,
                MiningNightCampGoal::isFarmFenceObstructionState,
                FARM_FENCE_CLEAR_DISTANCE_SQR
        ).orElse(null);
        if (clearTarget == null) {
            return false;
        }

        boolean started = this.farmFenceClearBlockAi.start(
                serverLevel,
                clearTarget,
                MiningNightCampGoal::isFarmFenceObstructionState,
                "clearing farm fence light route",
                FARM_FENCE_CLEAR_TICKS,
                FARM_FENCE_CLEAR_DISTANCE_SQR,
                true,
                true
        );
        if (!started || !this.isSafeActiveFarmFenceClearTarget(serverLevel)) {
            this.stopFarmFenceClear();
            return false;
        }
        this.farmFenceClearAttempts++;
        this.torchRepathTicks = 0;
        this.playerNpc.setCurrentAiDetail(this.farmFenceClearBlockAi.detail());
        return true;
    }

    private void tickFarmFenceClear(ServerLevel serverLevel) {
        if (!this.isSafeActiveFarmFenceClearTarget(serverLevel)) {
            this.clearTorchAction();
            this.playerNpc.setCurrentAiDetail("farm fence light clear cancelled: protected or out of bounds");
            return;
        }

        ClearBlockAi.TickResult result = this.farmFenceClearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            if (!this.isSafeActiveFarmFenceClearTarget(serverLevel)) {
                this.clearTorchAction();
                this.playerNpc.setCurrentAiDetail("farm fence light clear cancelled after blocker retarget");
                return;
            }
            this.playerNpc.setCurrentAiDetail(this.farmFenceClearBlockAi.detail());
            return;
        }

        this.stopFarmFenceClear();
        this.torchRepathTicks = 0;
        this.torchStandPos = this.findTorchStand(serverLevel, this.torchPos);
        if (this.torchStandPos == null
                || result == ClearBlockAi.TickResult.FAILED
                && this.farmFenceClearAttempts >= MAX_FARM_FENCE_CLEAR_ATTEMPTS) {
            this.clearTorchAction();
        }
    }

    private boolean isSafeActiveFarmFenceClearTarget(ServerLevel serverLevel) {
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        return plan != null
                && this.isOwnedFarmFenceLightTarget(serverLevel, plan, this.torchPos)
                && this.isSafeFarmFenceClearTarget(
                serverLevel,
                plan,
                this.farmFenceClearBlockAi.targetPos(),
                this.torchPos,
                this.torchStandPos
        );
    }

    private boolean isSafeFarmFenceClearTarget(
            ServerLevel serverLevel,
            Plan plan,
            BlockPos pos,
            BlockPos selectedTorchPos,
            BlockPos selectedStandPos
    ) {
        if (serverLevel == null
                || plan == null
                || pos == null
                || selectedTorchPos == null
                || !this.isOwnedFarmFenceLightTarget(serverLevel, plan, selectedTorchPos)
                || !serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        BlockPos fencePos = selectedTorchPos.below();
        boolean nearFence = Math.abs(pos.getX() - fencePos.getX()) <= FARM_FENCE_CLEAR_HORIZONTAL_RADIUS
                && Math.abs(pos.getZ() - fencePos.getZ()) <= FARM_FENCE_CLEAR_HORIZONTAL_RADIUS;
        boolean nearStand = selectedStandPos != null
                && Math.abs(pos.getX() - selectedStandPos.getX()) <= 1
                && Math.abs(pos.getZ() - selectedStandPos.getZ()) <= 1;
        if ((!nearFence && !nearStand)
                || pos.getY() < fencePos.getY()
                || pos.getY() > selectedTorchPos.getY() + FARM_FENCE_CLEAR_VERTICAL_ABOVE) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(pos);
        return isFarmFenceObstructionState(state)
                && serverLevel.getBlockEntity(pos) == null
                && !pos.equals(fencePos)
                && !pos.equals(plan.gatePos())
                && !pos.equals(plan.gatePos().above())
                && !plan.isFencePosition(pos)
                && !plan.containsGround(pos)
                && !pos.equals(plan.waterPos())
                && !this.playerNpc.isTemporaryPillarSupport(pos)
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && PlayerNpcHomeUtil.getHome(this.playerNpc)
                .map(home -> !PlayerNpcHomeUtil.isInside(home, pos))
                .orElse(true)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true);
    }

    private boolean isOwnedFarmFenceLightTarget(ServerLevel serverLevel, Plan plan, BlockPos pos) {
        return serverLevel != null
                && plan != null
                && pos != null
                && plan.phase().isReady()
                && FarmAi.farmTorchTargets(plan).contains(pos)
                && !pos.below().equals(plan.gatePos())
                && serverLevel.getBlockState(pos.below()).getBlock() instanceof net.minecraft.world.level.block.FenceBlock;
    }

    private boolean isFarmFenceTorchStandGeometry(ServerLevel serverLevel, Plan plan, BlockPos pos) {
        if (serverLevel == null
                || plan == null
                || pos == null
                || !serverLevel.isInWorldBounds(pos)
                || !serverLevel.isInWorldBounds(pos.above())
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || pos.equals(plan.gatePos())
                || plan.isFencePosition(pos)
                || plan.containsGround(pos.below())
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || PlayerNpcHomeUtil.getHome(this.playerNpc)
                .map(home -> PlayerNpcHomeUtil.isInside(home, pos))
                .orElse(false)) {
            return false;
        }
        return serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below())
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && serverLevel.getBlockEntity(pos.above()) == null;
    }

    private boolean canPrepareFarmFenceTorchStand(ServerLevel serverLevel, Plan plan, BlockPos pos) {
        if (!this.isFarmFenceTorchStandGeometry(serverLevel, plan, pos)) {
            return false;
        }
        for (BlockPos bodyPos : List.of(pos, pos.above())) {
            BlockState state = serverLevel.getBlockState(bodyPos);
            if (!state.getCollisionShape(serverLevel, bodyPos).isEmpty()
                    && !this.isSafeFarmFenceClearTarget(serverLevel, plan, bodyPos, this.torchPos, pos)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFarmFenceObstructionState(BlockState state) {
        return state != null
                && (state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.SNOW));
    }

    private void stopFarmFenceClear() {
        this.farmFenceClearBlockAi.stop();
        this.farmFenceClearToolAi.restoreMainHand();
    }

    private boolean hasNearbyTorch(ServerLevel serverLevel, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-TORCH_NEARBY_RADIUS, -2, -TORCH_NEARBY_RADIUS),
                center.offset(TORCH_NEARBY_RADIUS, 2, TORCH_NEARBY_RADIUS))) {
            BlockState state = serverLevel.getBlockState(pos);
            if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findCampWalkTarget(ServerLevel serverLevel) {
        BlockPos center = this.campCenter != null ? this.campCenter : this.playerNpc.blockPosition();
        boolean keepUnderground = GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && !serverLevel.canSeeSky(center.above());
        List<BlockPos> candidates = this.farmingCamp
                ? new ArrayList<>(this.farmCampWalkCandidates(serverLevel))
                : new ArrayList<>();
        if (!this.farmingCamp) {
            for (int dy = -CAMP_WALK_VERTICAL_RADIUS; dy <= CAMP_WALK_VERTICAL_RADIUS; dy++) {
                for (int dx = -CAMP_WALK_RADIUS; dx <= CAMP_WALK_RADIUS; dx++) {
                    for (int dz = -CAMP_WALK_RADIUS; dz <= CAMP_WALK_RADIUS; dz++) {
                        if (dx * dx + dz * dz > CAMP_WALK_RADIUS * CAMP_WALK_RADIUS) {
                            continue;
                        }
                        BlockPos candidate = center.offset(dx, dy, dz);
                        if (!candidate.equals(this.playerNpc.blockPosition())) {
                            candidates.add(candidate);
                        }
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(this.playerNpc.blockPosition())));
        int candidateChecks = 0;
        int pathChecks = 0;
        while (!candidates.isEmpty() && candidateChecks++ < CAMP_WALK_CANDIDATE_CHECKS) {
            BlockPos candidate = candidates.remove(this.playerNpc.getRandom().nextInt(candidates.size())).immutable();
            if (!PathNavigationAi.canStandAt(serverLevel, candidate)
                    || (keepUnderground && serverLevel.canSeeSky(candidate.above()))
                    || this.persistentBaseCamp
                    && this.campCenter != null
                    && horizontalDistanceSqr(candidate, this.campCenter) > FARM_CAMP_REACHED_SQR) {
                continue;
            }
            if (pathChecks++ >= CAMP_WALK_PATH_CHECKS) {
                break;
            }
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    CAMP_WALK_PATH_NODE_MULTIPLIER
            );
            if (path != null
                    && path.canReach()
                    && path.getEndNode() != null
                    && path.getEndNode().asBlockPos().equals(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private List<BlockPos> farmCampWalkCandidates(ServerLevel serverLevel) {
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (plan == null) {
            return List.of();
        }
        List<BlockPos> candidates = new ArrayList<>();
        int minX = plan.origin().getX() - 2;
        int maxX = plan.origin().getX() + plan.width() + 1;
        int minZ = plan.origin().getZ() - 2;
        int maxZ = plan.origin().getZ() + plan.depth() + 1;
        int baseFeetY = plan.origin().getY() + 1;
        for (int dy = -CAMP_WALK_VERTICAL_RADIUS; dy <= CAMP_WALK_VERTICAL_RADIUS; dy++) {
            int feetY = baseFeetY + dy;
            for (int x = minX; x <= maxX; x++) {
                candidates.add(new BlockPos(x, feetY, minZ));
                candidates.add(new BlockPos(x, feetY, maxZ));
            }
            for (int z = minZ + 1; z < maxZ; z++) {
                candidates.add(new BlockPos(minX, feetY, z));
                candidates.add(new BlockPos(maxX, feetY, z));
            }
        }
        return candidates.stream()
                .distinct()
                .filter(pos -> this.isSafeFarmExteriorStand(serverLevel, plan, pos))
                .map(BlockPos::immutable)
                .toList();
    }

    private BlockPos resolveFarmCampCenter(ServerLevel serverLevel) {
        Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
        if (plan == null) {
            return null;
        }
        // Camp is outside only.  Returning to one of these targets makes the normal
        // route cross the open gate; a bounded route failure may then use the explicit
        // outside return request handled by farm gate egress.
        List<BlockPos> candidates = new ArrayList<>();
        List<BlockPos> pathPositions = plan.pathPositions();
        if (pathPositions.isEmpty()) {
            return null;
        }
        BlockPos insideGateFeet = pathPositions.get(0).above();
        int outwardX = Integer.compare(plan.gatePos().getX(), insideGateFeet.getX());
        int outwardZ = Integer.compare(plan.gatePos().getZ(), insideGateFeet.getZ());
        if (Math.abs(outwardX) + Math.abs(outwardZ) != 1) {
            return null;
        }
        int lateralX = -outwardZ;
        int lateralZ = outwardX;
        int baseY = plan.gatePos().getY();
        for (int dy : new int[]{0, 1, -1, 2, -2}) {
            for (int forward = 1; forward <= 3; forward++) {
                for (int lateral = -2; lateral <= 2; lateral++) {
                    candidates.add(new BlockPos(
                            plan.gatePos().getX() + outwardX * forward + lateralX * lateral,
                            baseY + dy,
                            plan.gatePos().getZ() + outwardZ * forward + lateralZ * lateral
                    ));
                }
            }
        }
        List<BlockPos> safeCandidates = candidates.stream()
                .distinct()
                .filter(pos -> this.isSafeFarmExteriorStand(serverLevel, plan, pos))
                .map(BlockPos::immutable)
                .sorted(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr))
                .toList();
        if (safeCandidates.isEmpty()) {
            return null;
        }

        int pathChecks = 0;
        float pathNodeMultiplier = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                ? OVERLOADED_FARM_CAMP_SELECTION_PATH_NODE_MULTIPLIER
                : FARM_CAMP_SELECTION_PATH_NODE_MULTIPLIER;
        for (BlockPos candidate : safeCandidates) {
            if (this.playerNpc.blockPosition().equals(candidate)) {
                return candidate.immutable();
            }
            if (pathChecks++ >= FARM_CAMP_SELECTION_PATH_CHECKS) {
                break;
            }
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    pathNodeMultiplier
            );
            if (path != null
                    && path.canReach()
                    && path.getEndNode() != null
                    && path.getEndNode().asBlockPos().equals(candidate)) {
                return candidate.immutable();
            }
        }
        // Keep the outside destination even when the current navigation snapshot is
        // blocked. ReturnPositionAi will retry/relocate, and only that explicit outside
        // target is allowed to authorize farm gate egress.
        return safeCandidates.get(0).immutable();
    }

    private boolean isSafeFarmExteriorStand(ServerLevel serverLevel, Plan plan, BlockPos candidate) {
        return candidate != null
                && !candidate.equals(plan.gatePos())
                && !plan.isFencePosition(candidate)
                && !plan.containsGround(candidate.below())
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)
                && PathNavigationAi.canStandAt(serverLevel, candidate);
    }

    private boolean isWithinFarmCamp() {
        if (!this.farmingCamp || this.campCenter == null) {
            return true;
        }
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            Plan plan = FarmAi.getPlan(this.playerNpc, serverLevel).orElse(null);
            BlockPos feet = this.playerNpc.blockPosition();
            if (plan != null && (plan.containsGround(feet.below())
                    || feet.equals(plan.gatePos())
                    || plan.isFencePosition(feet))) {
                return false;
            }
        }
        double dx = this.playerNpc.getX() - (this.campCenter.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (this.campCenter.getZ() + 0.5D);
        return dx * dx + dz * dz <= FARM_CAMP_REACHED_SQR
                && Math.abs(this.playerNpc.getY() - this.campCenter.getY()) <= CAMP_WALK_VERTICAL_RADIUS + 1;
    }

    private boolean isWithinPersistentBaseCamp() {
        if (!this.persistentBaseCamp || this.campCenter == null) {
            return true;
        }
        double dx = this.playerNpc.getX() - (this.campCenter.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (this.campCenter.getZ() + 0.5D);
        return dx * dx + dz * dz <= FARM_CAMP_REACHED_SQR
                && Math.abs(this.playerNpc.getY() - this.campCenter.getY()) <= CAMP_WALK_VERTICAL_RADIUS + 1;
    }

    private boolean hasReachedWalkTarget() {
        return this.walkTarget != null
                && this.playerNpc.distanceToSqr(
                this.walkTarget.getX() + 0.5D,
                this.walkTarget.getY(),
                this.walkTarget.getZ() + 0.5D
        ) <= 1.25D;
    }

    private void switchCampActivity() {
        ActivityMode previous = this.activityMode;
        this.sneakingAi.stopSneaking();
        this.walkTarget = null;
        this.walkSneaking = false;
        this.activityMode = this.randomActivityMode(previous);
        this.activityTicks = this.nextActivityTicks();
        this.stationaryTicks = this.nextStationaryTicks();
        this.repathTicks = 0;
    }

    private ActivityMode randomActivityMode(ActivityMode previous) {
        ActivityMode next;
        do {
            float roll = this.playerNpc.getRandom().nextFloat();
            if (roll < 0.34F) {
                next = ActivityMode.SNEAK;
            } else if (roll < 0.72F) {
                next = ActivityMode.WALK;
            } else {
                next = ActivityMode.LOOK;
            }
        } while (next == previous);
        return next;
    }

    private int nextActivityTicks() {
        return MIN_ACTIVITY_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_ACTIVITY_TICKS + 1);
    }

    private int nextStationaryTicks() {
        return MIN_STATIONARY_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_STATIONARY_TICKS + 1);
    }

    private void lookAroundCamp() {
        if (this.lookTicks-- > 0) {
            return;
        }
        double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
        double distance = 3.0D + this.playerNpc.getRandom().nextDouble() * 4.0D;
        this.playerNpc.getLookControl().setLookAt(
                this.playerNpc.getX() + Math.cos(angle) * distance,
                this.playerNpc.getEyeY(),
                this.playerNpc.getZ() + Math.sin(angle) * distance,
                30.0F,
                30.0F
        );
        this.lookTicks = MIN_LOOK_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_LOOK_TICKS + 1);
    }

    private String activeAiState() {
        if (this.buildingBootstrapCamp) {
            return BUILDING_BOOTSTRAP_AI_STATE;
        }
        return this.farmingBootstrapCamp ? "ai.player_npc.farming" : AI_STATE;
    }

    private String campActivityContext() {
        if (this.buildingBootstrapCamp || this.farmingBootstrapCamp) {
            return this.farmingBootstrapCamp ? "temporary farm camp" : "temporary night camp";
        }
        return this.farmingCamp ? "farm camp"
                : this.persistentBaseCamp ? "permanent camp base"
                : "mining camp";
    }

    private void lookAtFurnace() {
        this.lookAt(this.furnacePos);
    }

    private void lookAt(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
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

    private ItemStack takeOrCraftTorch() {
        ItemStack torch = this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
        if (!torch.isEmpty()) {
            return torch;
        }
        int rawLogReserve = this.farmingCamp ? 0 : this.playerNpc.getRawLogReserveTarget();
        if (!PlayerNpcCraftingUtil.tryCraftTorches(this.playerNpc.getInventory(), rawLogReserve)) {
            return ItemStack.EMPTY;
        }
        return this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
    }

    private BlockPos getTemporaryFurnacePos() {
        if (FurnaceAi.TEMP_FURNACE_KIND_COOKING.equals(
                this.playerNpc.getPersistentData().getString(FurnaceAi.TEMP_FURNACE_KIND))
                || !this.playerNpc.getPersistentData().contains(FurnaceAi.TEMP_FURNACE_X)) {
            return null;
        }

        return new BlockPos(
                this.playerNpc.getPersistentData().getInt(FurnaceAi.TEMP_FURNACE_X),
                this.playerNpc.getPersistentData().getInt(FurnaceAi.TEMP_FURNACE_Y),
                this.playerNpc.getPersistentData().getInt(FurnaceAi.TEMP_FURNACE_Z)
        );
    }

    private boolean saveTemporaryFurnace(ServerLevel serverLevel, BlockPos pos) {
        if (!(serverLevel.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        furnace.getPersistentData().putUUID(CAMP_FURNACE_OWNER, this.playerNpc.getUUID());
        furnace.setChanged();

        CompoundTag data = this.playerNpc.getPersistentData();
        data.putInt(FurnaceAi.TEMP_FURNACE_X, pos.getX());
        data.putInt(FurnaceAi.TEMP_FURNACE_Y, pos.getY());
        data.putInt(FurnaceAi.TEMP_FURNACE_Z, pos.getZ());
        data.putString(FurnaceAi.TEMP_FURNACE_KIND, FurnaceAi.TEMP_FURNACE_KIND_NIGHT_CAMP);
        data.putInt(CAMP_FURNACE_X, pos.getX());
        data.putInt(CAMP_FURNACE_Y, pos.getY());
        data.putInt(CAMP_FURNACE_Z, pos.getZ());
        data.putString(CAMP_FURNACE_DIMENSION, serverLevel.dimension().location().toString());
        return true;
    }

    private void clearTemporaryFurnace() {
        this.playerNpc.getPersistentData().remove(FurnaceAi.TEMP_FURNACE_X);
        this.playerNpc.getPersistentData().remove(FurnaceAi.TEMP_FURNACE_Y);
        this.playerNpc.getPersistentData().remove(FurnaceAi.TEMP_FURNACE_Z);
        this.playerNpc.getPersistentData().remove(FurnaceAi.TEMP_FURNACE_KIND);
    }

    private boolean hasOwnedCampFurnaceReference() {
        CompoundTag data = this.playerNpc.getPersistentData();
        return data.contains(CAMP_FURNACE_X)
                && data.contains(CAMP_FURNACE_Y)
                && data.contains(CAMP_FURNACE_Z)
                && data.contains(CAMP_FURNACE_DIMENSION);
    }

    private CampFurnaceRef resolveOwnedCampFurnace() {
        if (!this.hasOwnedCampFurnaceReference()) {
            return null;
        }

        CompoundTag data = this.playerNpc.getPersistentData();
        BlockPos pos = new BlockPos(
                data.getInt(CAMP_FURNACE_X),
                data.getInt(CAMP_FURNACE_Y),
                data.getInt(CAMP_FURNACE_Z)
        );
        ResourceLocation dimensionId = ResourceLocation.tryParse(data.getString(CAMP_FURNACE_DIMENSION));
        if (dimensionId == null || this.playerNpc.getServer() == null) {
            this.clearCampFurnaceOwnership(pos);
            return null;
        }

        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel serverLevel = this.playerNpc.getServer().getLevel(levelKey);
        if (serverLevel == null || !serverLevel.isInWorldBounds(pos)) {
            this.clearCampFurnaceOwnership(pos);
            return null;
        }
        return new CampFurnaceRef(serverLevel, pos.immutable());
    }

    private boolean isOwnedCampFurnace(CampFurnaceRef ownedFurnace) {
        if (ownedFurnace == null
                || !ownedFurnace.level().getBlockState(ownedFurnace.pos()).is(Blocks.FURNACE)
                || !(ownedFurnace.level().getBlockEntity(ownedFurnace.pos()) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }
        CompoundTag furnaceData = furnace.getPersistentData();
        return furnaceData.hasUUID(CAMP_FURNACE_OWNER)
                && this.playerNpc.getUUID().equals(furnaceData.getUUID(CAMP_FURNACE_OWNER));
    }

    private boolean canReuseOwnedFurnace(ServerLevel currentLevel, CampFurnaceRef ownedFurnace) {
        if (ownedFurnace.level() != currentLevel) {
            return false;
        }
        BlockPos anchor = this.persistentBaseCamp && this.campCenter != null
                ? this.campCenter
                : this.playerNpc.blockPosition();
        int dx = ownedFurnace.pos().getX() - anchor.getX();
        int dz = ownedFurnace.pos().getZ() - anchor.getZ();
        return !this.isInsideOwnedFarmFurnaceExclusion(ownedFurnace.pos())
                && Math.abs(ownedFurnace.pos().getY() - anchor.getY()) <= 2
                && dx * dx + dz * dz <= FURNACE_SCAN_RADIUS * FURNACE_SCAN_RADIUS;
    }

    private boolean isInsideOwnedFarmFurnaceExclusion(BlockPos pos) {
        return FarmAi.isProtectedFarmBlock(this.playerNpc, pos)
                || FarmAi.isInsideOwnedFarmWorkOrEntranceFootprint(this.playerNpc, pos);
    }

    private void adoptNearbyLegacyTemporaryFurnace(ServerLevel currentLevel) {
        if (this.hasOwnedCampFurnaceReference()) {
            return;
        }
        String temporaryKind = this.playerNpc.getPersistentData().getString(FurnaceAi.TEMP_FURNACE_KIND);
        boolean explicitCamp = FurnaceAi.TEMP_FURNACE_KIND_NIGHT_CAMP.equals(temporaryKind);
        // Kindless records may be genuine old camp furnaces, but adopting them in
        // daytime also steals old CookFoodGoal furnaces. Migrate only while camping.
        if (!explicitCamp
                && (!temporaryKind.isBlank()
                || !shouldPauseMiningForNightCamp(this.playerNpc, currentLevel))) {
            return;
        }
        BlockPos legacyPos = this.getTemporaryFurnacePos();
        if (legacyPos == null
                || !currentLevel.hasChunkAt(legacyPos)
                || !this.isWithinLocalFurnaceScan(legacyPos)
                || !currentLevel.getBlockState(legacyPos).is(Blocks.FURNACE)
                || !(currentLevel.getBlockEntity(legacyPos) instanceof FurnaceBlockEntity furnace)) {
            return;
        }

        CompoundTag furnaceData = furnace.getPersistentData();
        if (furnaceData.hasUUID(CAMP_FURNACE_OWNER)
                && !this.playerNpc.getUUID().equals(furnaceData.getUUID(CAMP_FURNACE_OWNER))) {
            return;
        }
        this.saveTemporaryFurnace(currentLevel, legacyPos);
    }

    private boolean isWithinLocalFurnaceScan(BlockPos furnace) {
        BlockPos currentPos = this.playerNpc.blockPosition();
        int dx = furnace.getX() - currentPos.getX();
        int dz = furnace.getZ() - currentPos.getZ();
        return Math.abs(furnace.getY() - currentPos.getY()) <= 2
                && dx * dx + dz * dz <= FURNACE_SCAN_RADIUS * FURNACE_SCAN_RADIUS;
    }

    private boolean reclaimOwnedCampFurnace(CampFurnaceRef ownedFurnace) {
        if (ownedFurnace == null || !ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
            return false;
        }
        if (!this.isOwnedCampFurnace(ownedFurnace)) {
            this.clearCampFurnaceOwnership(ownedFurnace.pos());
            return true;
        }
        if (!(ownedFurnace.level().getBlockEntity(ownedFurnace.pos()) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        List<ItemStack> contents = new ArrayList<>(furnace.getContainerSize());
        for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
            contents.add(furnace.getItem(slot).copy());
            furnace.setItem(slot, ItemStack.EMPTY);
        }
        furnace.setChanged();
        if (!ownedFurnace.level().removeBlock(ownedFurnace.pos(), false)) {
            for (int slot = 0; slot < contents.size(); slot++) {
                furnace.setItem(slot, contents.get(slot));
            }
            furnace.setChanged();
            return false;
        }

        this.clearCampFurnaceOwnership(ownedFurnace.pos());
        for (ItemStack stack : contents) {
            this.returnStack(stack);
        }
        this.returnStack(new ItemStack(Items.FURNACE));
        this.playerNpc.triggerMainHandUseAnimation();
        ownedFurnace.level().playSound(
                null,
                ownedFurnace.pos(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.BLOCKS,
                0.5F,
                1.0F
        );
        return true;
    }

    private void clearCampFurnaceOwnership(BlockPos ownedPos) {
        if (ownedPos != null && ownedPos.equals(this.getTemporaryFurnacePos())) {
            this.clearTemporaryFurnace();
        }
        CompoundTag data = this.playerNpc.getPersistentData();
        data.remove(CAMP_FURNACE_X);
        data.remove(CAMP_FURNACE_Y);
        data.remove(CAMP_FURNACE_Z);
        data.remove(CAMP_FURNACE_DIMENSION);
    }

    private void clearActiveFurnaceAction(int cooldownTicks) {
        this.furnaceMode = FurnaceMode.NONE;
        this.furnacePos = null;
        this.furnaceStandPos = null;
        this.actionDelayTicks = 0;
        this.furnaceCooldownTicks = cooldownTicks;
    }

    private String detail(String action, BlockPos pos) {
        if (pos == null) {
            return action;
        }
        return String.format(Locale.ROOT, "%s @ %d %d %d", action, pos.getX(), pos.getY(), pos.getZ());
    }

    private void showPlacementItem(ItemStack stack) {
        this.setTemporaryMainHand(stack, false);
    }

    private void setTemporaryMainHand(ItemStack stack, boolean returnCurrentOnRestore) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryMainHand) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryMainHand = true;
            this.returnTemporaryMainHandOnRestore = returnCurrentOnRestore;
        } else if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)) {
            this.returnStack(currentMainHand);
        }

        ItemStack held = stack.copy();
        held.setCount(Math.min(1, held.getCount()));
        this.playerNpc.setMainHandItemForAi(held);
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryMainHand) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameComponents(currentMainHand, this.previousMainHand)) {
            this.returnStack(currentMainHand);
        }

        this.playerNpc.setMainHandItemForAi(this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
        this.returnTemporaryMainHandOnRestore = false;
    }

    private void finishPlacementMainHand() {
        if (!this.usingTemporaryMainHand) {
            return;
        }

        this.placingBlockAi.finishHeldPlacement(this.previousMainHand);
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
        this.returnTemporaryMainHandOnRestore = false;
    }

    private void returnStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remaining = this.playerNpc.getInventory().addItem(stack.copy());
        if (!remaining.isEmpty()) {
            this.playerNpc.spawnAtLocation(remaining);
        }
    }

    private void resetPlan() {
        this.campCenter = null;
        this.furnacePos = null;
        this.furnaceStandPos = null;
        this.walkTarget = null;
        this.torchPos = null;
        this.torchStandPos = null;
        this.resourcelessWalkProgressPos = null;
        this.furnaceMode = FurnaceMode.NONE;
        this.previousMainHand = ItemStack.EMPTY;
        this.actionDelayTicks = 0;
        this.furnaceCooldownTicks = 0;
        this.torchCheckTicks = 0;
        this.torchRepathTicks = 0;
        this.farmFenceClearAttempts = 0;
        this.activityTicks = 0;
        this.stationaryTicks = 0;
        this.lookTicks = 0;
        this.repathTicks = 0;
        this.furnaceRecoveryTicks = 0;
        this.resourcelessWalkRetryTicks = 0;
        this.resourcelessWalkNoProgressTicks = 0;
        this.resourcelessWalkCandidateCursor = 0;
        this.finished = false;
        this.placedTorch = false;
        this.farmingCamp = false;
        this.buildingBootstrapCamp = false;
        this.farmingBootstrapCamp = false;
        this.persistentBaseCamp = false;
        this.farmingBootstrapPlanCheckTicks = 0;
        this.walkSneaking = false;
        this.usingTemporaryMainHand = false;
        this.returnTemporaryMainHandOnRestore = false;
        this.reusableCampAnchor = false;
        this.resourcelessNightWalkActive = false;
        this.farmCampArrivalComplete = false;
        this.cachedContinueContext = true;
        this.nextContinueContextCheckTick = 0;
    }

    private enum FurnaceMode {
        NONE,
        PLACE,
        INTERACT,
        RECOVER
    }

    private record CampFurnaceRef(ServerLevel level, BlockPos pos) {
    }

    private record NightWalkPlan(BlockPos target, Path path) {
    }

    private enum ActivityMode {
        SNEAK,
        WALK,
        LOOK
    }
}
