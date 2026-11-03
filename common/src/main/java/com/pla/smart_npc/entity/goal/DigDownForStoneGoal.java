package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class DigDownForStoneGoal extends Goal implements GatheringGoal {
    private static final int MIN_HOME_DISTANCE = 18;
    private static final int DIG_SITE_MIN_RADIUS = 10;
    private static final int DIG_SITE_MAX_RADIUS = 24;
    private static final int LOCAL_RESOURCE_RADIUS = 96;
    private static final int MAX_GOAL_TICKS = 20 * 120;
    private static final int MAX_STAIR_STEPS = 24;
    private static final int MAX_MINE_TICKS = 20 * 8;
    private static final double BREAK_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double LOCAL_STEP_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int REPATH_INTERVAL_TICKS = 15;
    private static final int COOLDOWN_TICKS = 20 * 18;
    private static final int FAILED_WALK_COOLDOWN_TICKS = 20 * 2;
    private static final int MINING_JOB_DIG_BLOCKS = 16;
    private static final int MINING_PROSPECT_COOLDOWN_TICKS = 20;
    private static final int FISHING_SUPPORT_RETRY_COOLDOWN_TICKS = 20;
    private static final int ORE_SEARCH_INTERVAL_TICKS = 20 * 2;
    private static final int CONTINUE_ELIGIBILITY_INTERVAL_TICKS = 20;
    // Heightmap reads can initialize/inspect substantial chunk data even when the chunk is
    // already loaded. Keep each admitted activation probe genuinely small; the retained cursor
    // eventually covers the same radius without a 64-column server-thread burst.
    private static final int MAX_DIG_SITE_COLUMNS_PER_SLICE = 1;
    // Origin discovery is a speculative activation check. A single 0.05 path still
    // measured above 100 ms in live worlds, so keep it substantially below movement
    // paths and let later admitted checks try another candidate.
    private static final float DIG_SITE_PATH_NODE_MULTIPLIER = 0.01F;
    private static final int CAVE_CHECK_INTERVAL_TICKS = 20;
    private static final int MAX_DIG_SITE_WALK_TICKS = 20 * 25;
    private static final int LOCAL_PROSPECT_STUCK_TICKS = 20 * 2;
    private static final int LOCAL_PROSPECT_CLEAR_STUCK_TICKS = 20 * 3;
    private static final int MAX_DIG_SITE_SAFE_DROP_BLOCKS = 3;
    private static final int LOCAL_DIG_ROUTE_STEP_BLOCKS = 4;
    private static final float LOCAL_DIG_ROUTE_PATH_NODE_MULTIPLIER = 0.10F;
    private static final int CLEAR_OBSTRUCTION_TICKS = 24;
    private static final double CLEAR_OBSTRUCTION_DISTANCE_SQR = 5.0D * 5.0D;
    private static final int IDLE_DIAGNOSTIC_TICKS = 20 * 20;
    private static final int MINING_HANDOFF_TICKS = 20 * 3;
    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final Set<BlockPos> skippedClearTargets = new HashSet<>();
    private BlockPos digOrigin;
    private BlockPos targetPos;
    private BlockPos digStepOffset;
    private BlockPos lastLocalProspectWalkPos;
    private BlockPos activeClearTarget;
    private BlockPos digOriginSearchCenter;
    private BlockPos digOriginCandidateToValidate;
    private int digOriginSearchOffset;
    private int digOriginColumnsScanned;
    private boolean digOriginSearchPending;
    private int goalTicks;
    private int repathTicks;
    private int digSiteWalkTicks;
    private int stairSteps;
    private int stoneBlocksMined;
    private int stoneBlocksNeeded;
    private int nextProspectCheckTick;
    private int nextCaveCheckTick;
    private int nextPriorityTargetSearchTick;
    private int nextContinueEligibilityCheckTick;
    private int localProspectStillTicks;
    private int activeClearTargetTicks;
    private boolean minedStone;
    private boolean foundGatherStoneTarget;
    private boolean prospectingOre;
    private boolean reachedDigSite;
    private boolean finished;
    private boolean continueEligibilityAllowed = true;
    private String stopReason = "";
    private BlockPos miningHandoffOrigin;
    private long miningHandoffUntil;

    public DigDownForStoneGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0) {
            this.traceCanUseBlocked("digdown blocked: basic state");
            return false;
        }
        if (this.playerNpc.getGatherCooldown() > 0) {
            return false;
        }
        if (MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            this.traceCanUseBlocked("digdown blocked: mining night camp");
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        boolean miningJob = GatherStoneGoal.isMiningJobActive(this.playerNpc);
        boolean stoneSupplyActive = GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel);
        boolean miningProspecting = this.isMiningProspecting(serverLevel);
        if (this.shouldStayHomeForWeather(serverLevel) && !miningJob) {
            this.traceCanUseBlocked("digdown blocked: weather home");
            return false;
        }
        if (!this.hasPickaxe()) {
            this.traceCanUseBlocked("digdown blocked: no pickaxe");
            return false;
        }
        if (!stoneSupplyActive && !miningProspecting) {
            this.traceCanUseBlocked("digdown blocked: no mining phase logsNeed="
                    + this.playerNpc.shouldPrioritizeLogGathering()
                    + " stoneNeed=" + this.playerNpc.shouldPrioritizeCobblestoneGathering()
                    + " prepared=" + this.hasPreparedBaseForStone(serverLevel));
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.canUseThrottle.retryIn(this.playerNpc, 1 + this.playerNpc.getRandom().nextInt(4));
            return false;
        }
        if (stoneSupplyActive
                && GatherStoneGoal.hasCachedNearbyStoneTarget(this.playerNpc, serverLevel)) {
            this.traceCanUseBlocked("digdown blocked: nearby stone target; gather stone should run");
            return false;
        }
        if (miningProspecting) {
            if (this.playerNpc.tickCount < this.nextProspectCheckTick) {
                return false;
            }
            this.nextProspectCheckTick = this.playerNpc.tickCount + ORE_SEARCH_INTERVAL_TICKS;
        }

        this.prospectingOre = miningProspecting;

        this.digOrigin = this.findDigOrigin(serverLevel);
        if (this.digOrigin == null) {
            if (this.digOriginSearchPending) {
                int retryTicks = 1 + this.playerNpc.getRandom().nextInt(4);
                this.canUseThrottle.retryIn(this.playerNpc, retryTicks);
                return false;
            }
            if (stoneSupplyActive && GatherStoneGoal.isFishingSupportJob(this.playerNpc)) {
                // GatherStoneGoal has already found no usable nearby target at this point.
                // Back off only after the dig-down fallback also fails, otherwise this
                // shared cooldown would prevent a valid dig route from starting.
                this.playerNpc.setGatherCooldown(FISHING_SUPPORT_RETRY_COOLDOWN_TICKS);
            }
            this.traceCanUseBlocked("digdown blocked: no dig origin logsNeed="
                    + this.playerNpc.shouldPrioritizeLogGathering()
                    + " stoneNeed=" + this.playerNpc.shouldPrioritizeCobblestoneGathering()
                    + " prepared=" + this.hasPreparedBaseForStone(serverLevel)
                    + " gatherCooldown=" + this.playerNpc.getGatherCooldown());
            return false;
        }
        this.digStepOffset = this.chooseDigStepOffset();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.finished) {
            return false;
        }
        if (this.goalTicks >= MAX_GOAL_TICKS) {
            this.markStopReason("goal timeout");
            return false;
        }
        if (this.stairSteps >= MAX_STAIR_STEPS) {
            this.markStopReason("max stair steps");
            return false;
        }
        if (!this.prospectingOre && this.stoneBlocksMined >= this.stoneBlocksNeeded) {
            this.markStopReason("stone target met");
            return false;
        }
        if (!this.playerNpc.isAlive() || this.playerNpc.isNoAi()) {
            this.markStopReason("npc unavailable");
            return false;
        }
        if (this.playerNpc.getTarget() != null) {
            this.markStopReason("combat target");
            return false;
        }
        if (this.playerNpc.getUpwardEscapeTarget() != null) {
            this.markStopReason("upward escape requested");
            return false;
        }
        if (this.playerNpc.getHoleEscapeCooldown() > 0) {
            this.markStopReason("hole escape cooldown=" + this.playerNpc.getHoleEscapeCooldown());
            return false;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.markStopReason("level unavailable");
            return false;
        }
        if (this.playerNpc.tickCount >= this.nextContinueEligibilityCheckTick) {
            this.nextContinueEligibilityCheckTick = this.playerNpc.tickCount
                    + CONTINUE_ELIGIBILITY_INTERVAL_TICKS;
            this.continueEligibilityAllowed = this.checkContinueEligibility(serverLevel);
        }
        return this.continueEligibilityAllowed;
    }

    private boolean checkContinueEligibility(ServerLevel serverLevel) {
        if (!GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                && !this.isMiningProspecting(serverLevel)) {
            this.markStopReason("mining phase ended logsNeed="
                    + this.playerNpc.shouldPrioritizeLogGathering()
                    + " stoneNeed=" + this.playerNpc.shouldPrioritizeCobblestoneGathering());
            return false;
        }
        if (this.shouldStayHomeForWeather(serverLevel) && !GatherStoneGoal.isMiningJobActive(this.playerNpc)) {
            this.markStopReason("weather home");
            return false;
        }
        if (MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            this.markStopReason("mining night camp");
            return false;
        }
        if (!this.hasPreparedBaseForStone(serverLevel)) {
            this.markStopReason("base prep work active");
            return false;
        }
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.clearMiningHandoff();
        this.goalTicks = 0;
        this.repathTicks = 0;
        this.digSiteWalkTicks = 0;
        this.stairSteps = 0;
        this.stoneBlocksMined = 0;
        this.nextPriorityTargetSearchTick = this.playerNpc.tickCount + ORE_SEARCH_INTERVAL_TICKS;
        this.nextContinueEligibilityCheckTick = this.playerNpc.tickCount
                + CONTINUE_ELIGIBILITY_INTERVAL_TICKS;
        this.continueEligibilityAllowed = true;
        this.nextCaveCheckTick = 0;
        this.activeClearTargetTicks = 0;
        this.stoneBlocksNeeded = GatherStoneGoal.isMiningJobActive(this.playerNpc)
                ? MINING_JOB_DIG_BLOCKS
                : GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                ? Math.max(1, FarmAi.REQUIRED_STONE - this.countStone())
                : Math.max(1, this.playerNpc.getCobblestoneSupplyTarget() - this.countStone());
        this.targetPos = null;
        this.lastLocalProspectWalkPos = null;
        this.activeClearTarget = null;
        this.skippedClearTargets.clear();
        this.minedStone = false;
        this.foundGatherStoneTarget = false;
        this.reachedDigSite = false;
        this.finished = false;
        this.stopReason = "";
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.prospectingOre = this.isMiningProspecting(serverLevel);
        }
        this.playerNpc.setCurrentAiState(this.prospectingOre
                ? "ai.player_npc.prospecting_ore"
                : "ai.player_npc.digging_down_for_stone");
        this.updateWalkDetail();
        // Route creation is deferred to tick(), where it must obtain its own expensive-work slice.
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.digOrigin == null || this.digStepOffset == null) {
            this.finish("invalid dig context");
            return;
        }

        this.goalTicks++;
        if (this.prospectingOre
                && this.targetPos == null
                && this.playerNpc.tickCount >= this.nextCaveCheckTick) {
            if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                this.nextCaveCheckTick = this.playerNpc.tickCount + 1 + this.playerNpc.getRandom().nextInt(4);
            } else {
                this.nextCaveCheckTick = this.playerNpc.tickCount + CAVE_CHECK_INTERVAL_TICKS;
                if (MiningCaveStrollGoal.hasLongTraversableCave(this.playerNpc, serverLevel)) {
                    this.foundGatherStoneTarget = true;
                    this.finish("reached cave");
                    this.clearBlockAi.stop();
                    this.breakingBlockAi.stop();
                    this.playerNpc.getNavigation().stop();
                    this.playerNpc.setCurrentAiDetail("cave found for exploration");
                    return;
                }
            }
        }
        if (this.shouldYieldToHigherPriorityMiningTarget(serverLevel)) {
            this.foundGatherStoneTarget = true;
            this.finish(this.prospectingOre ? "yielded to ore target" : "yielded to stone target");
            this.clearBlockAi.stop();
            this.breakingBlockAi.stop();
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setCurrentAiDetail(this.prospectingOre
                    ? "ore found for mining"
                    : "stone exposed for gathering");
            return;
        }

        if (this.tickClearBlock(serverLevel)) {
            return;
        }

        if (!this.hasReachedDigOrigin(serverLevel)) {
            this.digSiteWalkTicks++;
            this.updateWalkDetail();
            if (this.digSiteWalkTicks > MAX_DIG_SITE_WALK_TICKS) {
                if (this.prospectingOre) {
                    this.recoverFromProspectRouteFailure();
                    return;
                }
                this.finish("walk to dig origin timed out");
                return;
            }
            if (this.tryMoveToLocalProspectingOrigin(serverLevel)) {
                return;
            }
            if (this.repathTicks-- <= 0) {
                if (this.hasHealthyDigOriginPath()) {
                    this.repathTicks = REPATH_INTERVAL_TICKS;
                    return;
                }
                if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                    this.repathTicks = 1 + this.playerNpc.getRandom().nextInt(4);
                    this.playerNpc.setCurrentAiDetail("walking to dig site; route queued for shared expensive-work slice");
                    return;
                }
                boolean movementStarted = this.moveTo(serverLevel, this.digOrigin);
                if (!movementStarted) {
                    if (this.prospectingOre) {
                        if (!this.startClearingDigRoute(serverLevel)) {
                            this.recoverFromProspectRouteFailure();
                        }
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        return;
                    }
                    if (this.shouldAbandonUnreachableInitialSite() || !this.startClearingDigRoute(serverLevel)) {
                        this.finish("dig origin unreachable");
                    }
                }
                // PathNavigationAi's safe-drop/local fallback deliberately drives MoveControl
                // while navigation remains done.  That command needs refreshing every tick;
                // applying the normal path repath delay turns a walk down ordinary terrain into
                // one short movement pulse every 16 ticks (visible as ~0.2 block/second).  Keep
                // the delay only while a real navigation path is active.  Worker ownership is
                // unchanged because this running work goal already holds its scheduler slot.
                this.repathTicks = movementStarted && this.playerNpc.getNavigation().isDone()
                        ? 0
                        : REPATH_INTERVAL_TICKS;
            }
            return;
        }
        this.reachedDigSite = true;
        this.digSiteWalkTicks = 0;

        if (this.targetPos == null) {
            this.targetPos = this.findNextDigTarget(serverLevel);
            if (this.targetPos == null) {
                if (!this.hasReached(this.digOrigin)) {
                    return;
                }
                this.finish("no dig target");
                return;
            }
        }

        this.tickMineTarget(serverLevel);
    }

    @Override
    public void stop() {
        this.clearMiningHandoff();
        if (this.foundGatherStoneTarget && this.digOrigin != null
                && this.playerNpc.blockPosition().distSqr(this.digOrigin) <= LOCAL_STEP_DISTANCE_SQR
                && this.playerNpc.getTarget() == null && !this.playerNpc.isHealing()
                && this.playerNpc.getUpwardEscapeTarget() == null) {
            // GoalSelector stops finished goals before admitting their replacements. Keep the
            // intentional excavation intact for a bounded selector/admission handoff to stone,
            // ore, or cave work; otherwise priority-one recovery can refill the new access step.
            this.miningHandoffOrigin = this.playerNpc.blockPosition().immutable();
            this.miningHandoffUntil = this.playerNpc.level().getGameTime() + MINING_HANDOFF_TICKS;
        }
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        int cooldown = -1;
        if (!this.playerNpc.level().isClientSide) {
            boolean miningProspecting = this.playerNpc.level() instanceof ServerLevel serverLevel
                    && this.isMiningProspecting(serverLevel);
            cooldown = this.foundGatherStoneTarget
                    ? 0
                    : miningProspecting
                    ? MINING_PROSPECT_COOLDOWN_TICKS
                    : this.minedStone
                    ? COOLDOWN_TICKS
                    : FAILED_WALK_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 2);
            this.playerNpc.setGatherCooldown(cooldown);
            if (!this.foundGatherStoneTarget && !miningProspecting && !this.minedStone) {
                // A failed stair direction/site is not a completed supply run. Give the other
                // supply goals a selector window to relocate before retrying this same site.
                this.canUseThrottle.retryIn(this.playerNpc, cooldown + 20);
            }
        }
        this.playerNpc.setIdleTraceDetail(this.stopTraceDetail(cooldown), IDLE_DIAGNOSTIC_TICKS);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.digOrigin = null;
        this.targetPos = null;
        this.digStepOffset = null;
        this.lastLocalProspectWalkPos = null;
        this.activeClearTarget = null;
        this.skippedClearTargets.clear();
        this.goalTicks = 0;
        this.repathTicks = 0;
        this.digSiteWalkTicks = 0;
        this.stairSteps = 0;
        this.nextPriorityTargetSearchTick = 0;
        this.nextContinueEligibilityCheckTick = 0;
        this.nextCaveCheckTick = 0;
        this.localProspectStillTicks = 0;
        this.activeClearTargetTicks = 0;
        this.minedStone = false;
        this.foundGatherStoneTarget = false;
        this.prospectingOre = false;
        this.reachedDigSite = false;
        this.finished = false;
        this.continueEligibilityAllowed = true;
        this.stopReason = "";
    }

    public boolean isMiningHandoffPending() {
        if (this.miningHandoffOrigin == null) {
            return false;
        }
        if (this.playerNpc.level().getGameTime() >= this.miningHandoffUntil
                || this.playerNpc.blockPosition().distSqr(this.miningHandoffOrigin) > LOCAL_STEP_DISTANCE_SQR
                || !this.playerNpc.isAlive() || this.playerNpc.isNoAi() || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing() || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null) {
            this.clearMiningHandoff();
            return false;
        }
        return true;
    }

    private void clearMiningHandoff() {
        this.miningHandoffOrigin = null;
        this.miningHandoffUntil = 0L;
    }

    private void finish(String reason) {
        this.markStopReason(reason);
        this.finished = true;
    }

    private void markStopReason(String reason) {
        if (reason != null && !reason.isBlank() && this.stopReason.isBlank()) {
            this.stopReason = reason;
        }
    }

    private void traceCanUseBlocked(String detail) {
        if (this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)) {
            this.playerNpc.setIdleTraceDetail(detail, IDLE_DIAGNOSTIC_TICKS);
        }
    }

    private String stopTraceDetail(int cooldown) {
        String reason = this.stopReason.isBlank() ? "selector stop/interrupted" : this.stopReason;
        return "digdown stop: " + reason
                + " prospect=" + this.prospectingOre
                + " foundTarget=" + this.foundGatherStoneTarget
                + " reached=" + this.reachedDigSite
                + " steps=" + this.stairSteps + "/" + MAX_STAIR_STEPS
                + " mined=" + this.stoneBlocksMined + "/" + this.stoneBlocksNeeded
                + " origin=" + posText(this.digOrigin)
                + " target=" + posText(this.targetPos)
                + " cooldown=" + cooldown
                + " logsNeed=" + this.playerNpc.shouldPrioritizeLogGathering()
                + " stoneNeed=" + this.playerNpc.shouldPrioritizeCobblestoneGathering()
                + " oreCooldown=" + this.playerNpc.getOreMiningCooldown()
                + " invFree=" + ExploreCaveOreGoal.freeInventorySlots(this.playerNpc)
                + " placeable=" + InventoryUtils.hasPlaceableBlock(this.playerNpc);
    }

    private BlockPos findDigOrigin(ServerLevel serverLevel) {
        if (this.prospectingOre) {
            BlockPos localOrigin = this.findCurrentProspectingOrigin(serverLevel);
            if (localOrigin != null) {
                return localOrigin;
            }
        }

        // Prefer the already-loaded stand when the NPC is plainly on open surface away from
        // protected work. This avoids asking a random neighbouring chunk to initialize a
        // MOTION_BLOCKING_NO_LEAVES heightmap merely to choose an equivalent dig start. Keep the
        // retained column search for roofs/holes, home-adjacent positions, and unsafe terrain so
        // this cannot revive the old "dig farther down while trapped" behavior.
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.playerNpc.onGround()
                && serverLevel.canSeeSky(feet.above())
                && this.canStandAt(serverLevel, feet)
                && this.isAwayFromHome(feet)
                && !this.isProtectedStoneWorkPosition(feet)
                && this.isInsideResourceRadius(feet)) {
            this.resetDigOriginSearch();
            return feet.immutable();
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        BlockPos center = home.map(PlayerNpcHomeUtil::center).orElseGet(() -> this.playerNpc.blockPosition().immutable());
        this.ensureDigOriginSearch(center);

        // A plausible loaded stand starts the running goal without speculative PathFinder work.
        // Movement owns its separately admitted bounded route on a later entity tick; a failed
        // route uses the existing short walk cooldown and the retained search chooses another.
        if (this.digOriginCandidateToValidate != null) {
            BlockPos candidate = this.digOriginCandidateToValidate;
            this.digOriginCandidateToValidate = null;
            this.resetDigOriginSearch();
            return candidate;
        }

        int area = this.digOriginSearchArea();
        int end = Math.min(area, this.digOriginColumnsScanned + MAX_DIG_SITE_COLUMNS_PER_SLICE);
        int diameter = DIG_SITE_MAX_RADIUS * 2 + 1;
        while (this.digOriginColumnsScanned < end) {
            int index = (this.digOriginSearchOffset + this.digOriginColumnsScanned++) % area;
            int dx = index % diameter - DIG_SITE_MAX_RADIUS;
            int dz = index / diameter - DIG_SITE_MAX_RADIUS;
            int distSqr = dx * dx + dz * dz;
            if (distSqr < DIG_SITE_MIN_RADIUS * DIG_SITE_MIN_RADIUS
                    || distSqr > DIG_SITE_MAX_RADIUS * DIG_SITE_MAX_RADIUS) {
                continue;
            }
            int x = center.getX() + dx;
            int z = center.getZ() + dz;
            if (!serverLevel.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (this.canStandAt(serverLevel, candidate)
                    && this.isAwayFromHome(candidate)
                    && !this.isProtectedStoneWorkPosition(candidate)
                    && this.isInsideResourceRadius(candidate)) {
                this.digOriginCandidateToValidate = candidate.immutable();
                this.digOriginSearchPending = true;
                return null;
            }
        }
        this.digOriginSearchPending = this.digOriginColumnsScanned < area;
        if (!this.digOriginSearchPending) {
            this.resetDigOriginSearch();
        }
        return null;
    }

    private void ensureDigOriginSearch(BlockPos center) {
        if (center.equals(this.digOriginSearchCenter)) {
            return;
        }
        this.resetDigOriginSearch();
        this.digOriginSearchCenter = center.immutable();
        this.digOriginSearchOffset = this.playerNpc.getRandom().nextInt(this.digOriginSearchArea());
        this.digOriginSearchPending = true;
    }

    private int digOriginSearchArea() {
        int diameter = DIG_SITE_MAX_RADIUS * 2 + 1;
        return diameter * diameter;
    }

    private void resetDigOriginSearch() {
        this.digOriginSearchCenter = null;
        this.digOriginCandidateToValidate = null;
        this.digOriginSearchOffset = 0;
        this.digOriginColumnsScanned = 0;
        this.digOriginSearchPending = false;
    }

    private BlockPos findCurrentProspectingOrigin(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.isValidLocalProspectingOrigin(serverLevel, feet)) {
            return feet.immutable();
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(feet.relative(direction));
            candidates.add(feet.relative(direction).below());
            candidates.add(feet.relative(direction).above());
        }

        candidates.sort(Comparator.comparingDouble(feet::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.isValidLocalProspectingOrigin(serverLevel, immutable)) {
                continue;
            }
            if (feet.distSqr(immutable) <= LOCAL_STEP_DISTANCE_SQR
                    || this.pathNavigationAi.canReachOrSafelyDropTo(
                    serverLevel,
                    immutable,
                    MAX_DIG_SITE_SAFE_DROP_BLOCKS,
                    DIG_SITE_PATH_NODE_MULTIPLIER
            )) {
                return immutable;
            }
        }
        return null;
    }

    private boolean isValidLocalProspectingOrigin(ServerLevel serverLevel, BlockPos pos) {
        return this.canStandAt(serverLevel, pos)
                && !this.isProtectedStoneWorkPosition(pos)
                && this.isInsideResourceRadius(pos)
                && (this.isAwayFromHome(pos) || !serverLevel.canSeeSky(pos.above()));
    }

    private BlockPos chooseDigStepOffset() {
        List<BlockPos> offsets = this.digStepOffsets();
        return offsets.get(this.playerNpc.getRandom().nextInt(offsets.size()));
    }

    private List<BlockPos> digStepOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                offsets.add(new BlockPos(dx, 0, dz));
            }
        }
        return offsets;
    }

    private BlockPos findNextDigTarget(ServerLevel serverLevel) {
        if (this.prospectingOre) {
            return this.findNextProspectingDigTarget(serverLevel);
        }

        BlockPos feet = this.playerNpc.blockPosition();

        if (this.shouldYieldToHigherPriorityMiningTarget(serverLevel)) {
            this.foundGatherStoneTarget = true;
            this.finish("yielded to stone target");
            return null;
        }

        BlockPos originBefore = this.digOrigin;
        BlockPos target = this.findDigTargetForOffset(serverLevel, feet, this.digStepOffset);
        if (target != null || this.originChanged(originBefore)) {
            return target;
        }
        // A single random direction may face protected terrain, water, or an open drop.
        // Check the other seven adjacent stair directions before declaring the site exhausted.
        // This is a fixed local block check, with no volume scan or path creation.
        for (BlockPos offset : this.digStepOffsets()) {
            if (offset.equals(this.digStepOffset)) {
                continue;
            }
            target = this.findDigTargetForOffset(serverLevel, feet, offset);
            if (target != null || this.originChanged(originBefore)) {
                this.digStepOffset = offset;
                return target;
            }
        }
        return null;
    }

    private BlockPos findNextProspectingDigTarget(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.shouldYieldToHigherPriorityMiningTarget(serverLevel)) {
            this.foundGatherStoneTarget = true;
            this.finish("yielded to ore target");
            return null;
        }

        List<BlockPos> offsets = this.digStepOffsets();
        if (this.digStepOffset != null && offsets.remove(this.digStepOffset)) {
            BlockPos originBefore = this.digOrigin;
            BlockPos target = this.findDigTargetForOffset(serverLevel, feet, this.digStepOffset);
            if (target != null || this.originChanged(originBefore)) {
                return target;
            }
        }

        while (!offsets.isEmpty()) {
            BlockPos offset = offsets.remove(this.playerNpc.getRandom().nextInt(offsets.size()));
            BlockPos originBefore = this.digOrigin;
            BlockPos target = this.findDigTargetForOffset(serverLevel, feet, offset);
            if (target != null || this.originChanged(originBefore)) {
                this.digStepOffset = offset;
                return target;
            }
        }

        BlockPos below = feet.below();
        if (this.isDiggable(serverLevel, below, serverLevel.getBlockState(below))) {
            return below.immutable();
        }
        if (this.canStandAt(serverLevel, below)) {
            this.advanceProspectingOrigin(serverLevel, below, this.chooseDigStepOffset());
        }
        return null;
    }

    private BlockPos findDigTargetForOffset(ServerLevel serverLevel, BlockPos feet, BlockPos offset) {
        if (offset == null) {
            return null;
        }

        BlockPos forwardHead = feet.offset(offset);
        BlockPos forwardFeet = forwardHead.below();
        if (!serverLevel.hasChunkAt(forwardHead) || !serverLevel.hasChunkAt(forwardFeet)) {
            return null;
        }

        if (this.isDiggable(serverLevel, forwardHead, serverLevel.getBlockState(forwardHead))) {
            return forwardHead.immutable();
        }
        if (this.isDiggable(serverLevel, forwardFeet, serverLevel.getBlockState(forwardFeet))) {
            return forwardFeet.immutable();
        }
        if (this.canStandAt(serverLevel, forwardFeet)) {
            this.advanceProspectingOrigin(serverLevel, forwardFeet, offset);
            return null;
        }
        return null;
    }

    private void advanceProspectingOrigin(ServerLevel serverLevel, BlockPos origin, BlockPos offset) {
        this.digOrigin = origin.immutable();
        this.digStepOffset = offset == null ? this.chooseDigStepOffset() : offset.immutable();
        this.reachedDigSite = false;
        this.digSiteWalkTicks = 0;
        this.resetLocalProspectingMovement();
        this.skippedClearTargets.clear();
        this.stairSteps++;
        this.repathTicks = 0;
        this.updateWalkDetail();
    }

    private boolean originChanged(BlockPos previousOrigin) {
        if (previousOrigin == null) {
            return this.digOrigin != null;
        }
        return !previousOrigin.equals(this.digOrigin);
    }

    private void tickMineTarget(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!this.isDiggable(serverLevel, this.targetPos, state)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            this.targetPos = null;
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            this.breakingBlockAi.stop();
            this.targetPos = null;
            return;
        }

        this.toolAi.equipBestToolFor(state);
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, this.targetPos, state);
        boolean targetIsStone = this.isStoneMaterial(state);
        BlockPos minedPos = this.targetPos;
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                targetState -> this.isDiggable(serverLevel, minedPos, targetState),
                requiredMineTicks,
                this.prospectingOre
                        ? "digging ore search path"
                        : targetIsStone ? "mining dig-site stone" : "digging stone search path"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        if (result == BreakingBlockAi.TickResult.DONE && targetIsStone) {
            this.minedStone = true;
            this.stoneBlocksMined++;
        }
        this.playerNpc.clearBlockBreakProgress(minedPos);
        this.targetPos = null;
    }

    private boolean isDiggable(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && !this.isProtectedHomeBlock(pos);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_MINE_TICKS;
        }

        ItemStack heldStack = this.playerNpc.getMainHandItem();
        float toolSpeed = heldStack.isEmpty() ? 1.0F : heldStack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_MINE_TICKS;
        }
        return Math.min(MAX_MINE_TICKS, Math.max(1, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private boolean tickClearBlock(ServerLevel serverLevel) {
        if (!this.clearBlockAi.isRunning()) {
            return false;
        }

        BlockPos clearTarget = this.clearBlockAi.targetPos();
        if (this.abortProtectedClearTarget(clearTarget)) {
            return true;
        }
        this.trackActiveClearTarget(clearTarget);
        ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
        BlockPos resolvedTarget = this.clearBlockAi.targetPos();
        if (this.abortProtectedClearTarget(resolvedTarget)) {
            return true;
        }
        if (result == ClearBlockAi.TickResult.RUNNING) {
            if (this.prospectingOre && this.activeClearTargetTicks >= LOCAL_PROSPECT_CLEAR_STUCK_TICKS) {
                this.abortSlowProspectClearTarget(clearTarget);
                return false;
            }
            return true;
        }

        if (result == ClearBlockAi.TickResult.FAILED && clearTarget != null) {
            this.skippedClearTargets.add(clearTarget.immutable());
        }
        this.activeClearTarget = null;
        this.activeClearTargetTicks = 0;
        this.repathTicks = 0;
        return false;
    }

    private boolean abortProtectedClearTarget(BlockPos clearTarget) {
        if (clearTarget == null || !this.isProtectedHomeBlock(clearTarget)) {
            return false;
        }

        BlockPos immutable = clearTarget.immutable();
        this.skippedClearTargets.add(immutable);
        this.playerNpc.clearBlockBreakProgress(immutable);
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.activeClearTarget = null;
        this.activeClearTargetTicks = 0;
        this.repathTicks = 0;
        this.recoverFromProspectRouteFailure();
        return true;
    }

    private void trackActiveClearTarget(BlockPos clearTarget) {
        if (clearTarget == null) {
            this.activeClearTarget = null;
            this.activeClearTargetTicks = 0;
            return;
        }

        if (!clearTarget.equals(this.activeClearTarget)) {
            this.activeClearTarget = clearTarget.immutable();
            this.activeClearTargetTicks = 0;
        }
        this.activeClearTargetTicks++;
    }

    private void abortSlowProspectClearTarget(BlockPos clearTarget) {
        if (clearTarget != null) {
            this.skippedClearTargets.add(clearTarget.immutable());
            this.playerNpc.clearBlockBreakProgress(clearTarget);
        }
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.activeClearTarget = null;
        this.activeClearTargetTicks = 0;
        this.repathTicks = 0;
        this.recoverFromProspectRouteFailure();
    }

    private boolean startClearingDigRoute(ServerLevel serverLevel) {
        if (this.digOrigin == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(feet, this.digOrigin, this.digOrigin));
        addLineCandidates(candidates, feet.above(), this.digOrigin.above(), 12);
        addLineCandidates(candidates, feet, this.digOrigin, 12);
        addLocalRouteCandidates(candidates, feet, this.digOrigin);
        candidates.removeIf(pos -> this.isProtectedHomeBlock(pos)
                || this.skippedClearTargets.contains(pos.immutable()));

        return this.clearBlockAi.startNearest(
                serverLevel,
                candidates,
                this::isClearablePathState,
                this.prospectingOre ? "clearing ore prospect path" : "clearing dig path",
                CLEAR_OBSTRUCTION_TICKS,
                CLEAR_OBSTRUCTION_DISTANCE_SQR,
                true
        );
    }

    private boolean isClearablePathState(BlockState state) {
        return state != null && !state.isAir();
    }

    private boolean moveTo(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        boolean moved = this.stairSteps == 0
                ? this.pathNavigationAi.moveTo(
                serverLevel,
                pos,
                this.speed,
                MAX_DIG_SITE_SAFE_DROP_BLOCKS,
                DIG_SITE_PATH_NODE_MULTIPLIER
        )
                : this.pathNavigationAi.moveToExact(
                serverLevel,
                pos,
                this.speed,
                MAX_DIG_SITE_SAFE_DROP_BLOCKS,
                DIG_SITE_PATH_NODE_MULTIPLIER
        );
        if (moved) {
            return true;
        }
        if (this.stairSteps == 0 && this.moveToLocalDigWaypoint(serverLevel, pos)) {
            return true;
        }
        if (this.playerNpc.blockPosition().distSqr(pos) <= LOCAL_STEP_DISTANCE_SQR) {
            this.playerNpc.getMoveControl().setWantedPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, this.speed);
            return true;
        }
        return false;
    }

    /**
     * The deliberately tiny full-route node budget protects the server from pathological
     * 10-24-block path builds, but it cannot finish even an ordinary medium-distance surface
     * route reliably.  Advance through one bounded four-block surface segment instead.  This is
     * only used while approaching the initial dig site; stair excavation keeps its exact route.
     */
    private boolean moveToLocalDigWaypoint(ServerLevel serverLevel, BlockPos target) {
        BlockPos feet = this.playerNpc.blockPosition();
        int dx = target.getX() - feet.getX();
        int dz = target.getZ() - feet.getZ();
        double horizontalDistance = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (horizontalDistance <= LOCAL_DIG_ROUTE_STEP_BLOCKS) {
            return false;
        }

        int waypointX = feet.getX() + (int) Math.round(dx / horizontalDistance * LOCAL_DIG_ROUTE_STEP_BLOCKS);
        int waypointZ = feet.getZ() + (int) Math.round(dz / horizontalDistance * LOCAL_DIG_ROUTE_STEP_BLOCKS);
        if (!serverLevel.hasChunk(waypointX >> 4, waypointZ >> 4)) {
            return false;
        }

        int waypointY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, waypointX, waypointZ);
        BlockPos waypoint = new BlockPos(waypointX, waypointY, waypointZ);
        if (!this.canStandAt(serverLevel, waypoint)
                || this.isProtectedStoneWorkPosition(waypoint)
                || !this.isInsideResourceRadius(waypoint)) {
            return false;
        }

        Path path = PathNavigationAi.createBoundedPath(
                this.playerNpc,
                waypoint,
                LOCAL_DIG_ROUTE_PATH_NODE_MULTIPLIER
        );
        return this.pathNavigationAi.isValidPathTo(waypoint, path)
                && this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private boolean hasHealthyDigOriginPath() {
        BlockPos navigationTarget = this.playerNpc.getNavigation().getTargetPos();
        return this.digOrigin != null
                && navigationTarget != null
                && navigationTarget.equals(this.digOrigin)
                && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck();
    }

    private boolean tryMoveToLocalProspectingOrigin(ServerLevel serverLevel) {
        if (!this.prospectingOre
                || this.digOrigin == null
                || this.playerNpc.blockPosition().distSqr(this.digOrigin) > LOCAL_STEP_DISTANCE_SQR
                || !this.isValidLocalProspectingOrigin(serverLevel, this.digOrigin)) {
            return false;
        }

        boolean stalled = this.hasLocalProspectingMovementStalled();
        if ((this.hasBlockedLocalProspectingRoute(serverLevel) || stalled)
                && this.startClearingDigRoute(serverLevel)) {
            this.resetLocalProspectingMovement();
            return true;
        }
        if (stalled) {
            this.recoverFromProspectRouteFailure();
            return true;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.digOrigin.getX() + 0.5D,
                this.digOrigin.getY(),
                this.digOrigin.getZ() + 0.5D,
                30.0F,
                30.0F
        );
        this.playerNpc.getMoveControl().setWantedPosition(
                this.digOrigin.getX() + 0.5D,
                this.digOrigin.getY(),
                this.digOrigin.getZ() + 0.5D,
                this.speed
        );
        return true;
    }

    private boolean hasBlockedLocalProspectingRoute(ServerLevel serverLevel) {
        if (this.digOrigin == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.hasBlockingBodyColumn(serverLevel, feet)
                || this.hasBlockingBodyColumn(serverLevel, this.digOrigin)) {
            return true;
        }

        int stepX = Integer.compare(this.digOrigin.getX(), feet.getX());
        int stepZ = Integer.compare(this.digOrigin.getZ(), feet.getZ());
        if (stepX == 0 || stepZ == 0) {
            return false;
        }

        return this.hasBlockingBodyColumn(serverLevel, feet.offset(stepX, 0, 0))
                || this.hasBlockingBodyColumn(serverLevel, feet.offset(0, 0, stepZ));
    }

    private boolean hasBlockingBodyColumn(ServerLevel serverLevel, BlockPos feet) {
        return this.hasBreakableBodySpaceBlocker(serverLevel, feet)
                || this.hasBreakableBodySpaceBlocker(serverLevel, feet.above());
    }

    private boolean hasBreakableBodySpaceBlocker(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return !this.isProtectedHomeBlock(pos)
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true);
    }

    private boolean hasLocalProspectingMovementStalled() {
        BlockPos feet = this.playerNpc.blockPosition();
        if (!feet.equals(this.lastLocalProspectWalkPos)) {
            this.lastLocalProspectWalkPos = feet.immutable();
            this.localProspectStillTicks = 0;
            return false;
        }

        return ++this.localProspectStillTicks >= LOCAL_PROSPECT_STUCK_TICKS;
    }

    private void resetLocalProspectingMovement() {
        this.lastLocalProspectWalkPos = null;
        this.localProspectStillTicks = 0;
    }

    private void recoverFromProspectRouteFailure() {
        if (!this.prospectingOre || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.finish("prospect route recovery unavailable");
            return;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.isValidLocalProspectingOrigin(serverLevel, feet)) {
            this.finish("prospect route recovery invalid stand");
            return;
        }

        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.playerNpc.getNavigation().stop();
        this.targetPos = null;
        this.digOrigin = feet.immutable();
        this.digStepOffset = this.chooseDigStepOffset();
        this.reachedDigSite = true;
        this.digSiteWalkTicks = 0;
        this.repathTicks = 0;
        this.resetLocalProspectingMovement();
        this.playerNpc.setCurrentAiDetail("prospecting nearby ore");
    }

    private boolean shouldAbandonUnreachableInitialSite() {
        return this.stairSteps == 0
                && this.digOrigin != null
                && this.playerNpc.blockPosition().distSqr(this.digOrigin) > LOCAL_STEP_DISTANCE_SQR;
    }

    private boolean shouldYieldToHigherPriorityMiningTarget(ServerLevel serverLevel) {
        if (this.playerNpc.tickCount < this.nextPriorityTargetSearchTick) {
            return false;
        }
        this.nextPriorityTargetSearchTick = this.playerNpc.tickCount + ORE_SEARCH_INTERVAL_TICKS;
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.nextPriorityTargetSearchTick = this.playerNpc.tickCount
                    + 1
                    + this.playerNpc.getRandom().nextInt(4);
            return false;
        }
        if (this.prospectingOre) {
            if (this.playerNpc.getOreMiningCooldown() > 0) {
                return false;
            }
            if (ExploreCaveOreGoal.isOreInventoryBlocked(this.playerNpc)) {
                return false;
            }
            return ExploreCaveOreGoal.hasNearbyOreTarget(this.playerNpc, serverLevel);
        }

        return GatherStoneGoal.hasNearbyStoneTarget(this.playerNpc, serverLevel);
    }

    private boolean hasReached(BlockPos pos) {
        return pos != null && this.playerNpc.blockPosition().equals(pos);
    }

    private boolean hasReachedDigOrigin(ServerLevel serverLevel) {
        if (this.hasReached(this.digOrigin)) {
            return true;
        }
        if (!this.prospectingOre
                || this.digOrigin == null
                || this.stairSteps > 0) {
            return false;
        }

        // The initial local prospect may safely start from the NPC's current stand instead of
        // requiring an exact adjacent route endpoint. Once a stair step has been advanced, however,
        // accepting another merely-nearby stand rewrites every new origin back to the unchanged
        // feet position. Open cave air then consumes all MAX_STAIR_STEPS without any movement.
        // Require exact arrival after the first advance so normal local-stall recovery can clear,
        // re-anchor at a genuinely safe current stand, or end the attempt for a later retry.
        BlockPos feet = this.playerNpc.blockPosition();
        if (feet.distSqr(this.digOrigin) > LOCAL_STEP_DISTANCE_SQR
                || !this.isValidLocalProspectingOrigin(serverLevel, feet)
                || this.hasBlockingBodyColumn(serverLevel, feet)) {
            return false;
        }

        this.digOrigin = feet.immutable();
        return true;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private boolean isAwayFromHome(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return true;
        }
        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos homeCenter = PlayerNpcHomeUtil.center(homeArea);
        return homeCenter.distSqr(pos) >= MIN_HOME_DISTANCE * MIN_HOME_DISTANCE;
    }

    private boolean isInsideResourceRadius(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideActivityRadius(this.playerNpc, pos, LOCAL_RESOURCE_RADIUS, true);
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                || FarmAi.isProtectedFarmBlock(this.playerNpc, pos)
                || FarmAi.isBelowOwnedFarmFootprint(this.playerNpc, pos);
    }

    private boolean isProtectedStoneWorkPosition(BlockPos pos) {
        return this.isProtectedHomeBlock(pos)
                || FarmAi.isInsideOwnedFarmWorkOrEntranceFootprint(this.playerNpc, pos);
    }

    private boolean hasPreparedBaseForStone(ServerLevel serverLevel) {
        if (GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || GatherStoneGoal.isFishingSupportJob(this.playerNpc)
                || GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                || GatherStoneGoal.isExploringSupplyJob(this.playerNpc)) {
            return true;
        }

        return PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && !TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel);
    }

    private boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && (serverLevel.isNight() || serverLevel.isThundering());
    }

    private boolean isMiningProspecting(ServerLevel serverLevel) {
        return GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && !this.playerNpc.shouldPrioritizeLogGathering()
                && !this.playerNpc.shouldPrioritizeCobblestoneGathering()
                && this.hasPreparedBaseForStone(serverLevel);
    }

    private boolean hasPickaxe() {
        return this.playerNpc.hasCarriedTool(PickaxeItem.class);
    }

    private int countStone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private boolean isStoneMaterial(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }

    private void updateWalkDetail() {
        if (this.digOrigin == null) {
            this.playerNpc.setCurrentAiDetail(this.prospectingOre
                    ? "walking to ore prospect"
                    : "walking to dig site");
            return;
        }
        this.playerNpc.setCurrentAiDetail((this.prospectingOre
                ? "walking to ore prospect @ "
                : "walking to dig site @ ")
                + this.digOrigin.getX() + " "
                + this.digOrigin.getY() + " "
                + this.digOrigin.getZ());
    }

    private static String posText(BlockPos pos) {
        return pos == null ? "none" : pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void addBodyColumn(List<BlockPos> candidates, BlockPos feet) {
        if (feet == null) {
            return;
        }
        candidates.add(feet);
        candidates.add(feet.above());
        candidates.add(feet.above(2));
    }

    private static void addLocalRouteCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos target) {
        if (feet == null) {
            return;
        }

        if (target == null) {
            return;
        }

        int stepX = Integer.compare(target.getX(), feet.getX());
        int stepZ = Integer.compare(target.getZ(), feet.getZ());
        if (stepX != 0) {
            addBodyColumn(candidates, feet.offset(stepX, 0, 0));
            candidates.add(feet.offset(stepX, -1, 0));
        }
        if (stepZ != 0) {
            addBodyColumn(candidates, feet.offset(0, 0, stepZ));
            candidates.add(feet.offset(0, -1, stepZ));
        }
        if (stepX != 0 && stepZ != 0) {
            addBodyColumn(candidates, feet.offset(stepX, 0, stepZ));
            candidates.add(feet.offset(stepX, -1, stepZ));
        }
    }

    private static void addLineCandidates(List<BlockPos> candidates, BlockPos start, BlockPos target, int maxSteps) {
        if (start == null || target == null) {
            return;
        }
        double dx = target.getX() - start.getX();
        double dy = target.getY() - start.getY();
        double dz = target.getZ() - start.getZ();
        int steps = Math.max(1, Math.min(maxSteps, (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))))));
        for (int i = 1; i <= steps; i++) {
            double progress = i / (double) steps;
            int x = start.getX() + (int) Math.round(dx * progress);
            int y = start.getY() + (int) Math.round(dy * progress);
            int z = start.getZ() + (int) Math.round(dz * progress);
            candidates.add(new BlockPos(x, y, z));
        }
    }
}
