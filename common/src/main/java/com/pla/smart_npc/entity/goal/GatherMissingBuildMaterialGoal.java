package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.entity.ai.VanillaMeleeAttackAi;
import com.pla.smart_npc.entity.ai.WeaponAi;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcAdaptiveSearchScope;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil.MissingBuildMaterialKind;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil.MissingBuildMaterialNeed;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public class GatherMissingBuildMaterialGoal extends Goal implements GatheringGoal {
    private static final int SEARCH_RADIUS = 32;
    private static final int MAX_TARGET_SCAN_COLUMNS_PER_SLICE = 8;
    private static final int TARGET_SCAN_CACHE_TICKS = 20;
    private static final int MAX_GATHER_TICKS = 20 * 45;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int UNARMED_SHEEP_ATTACK_INTERVAL_TICKS = 14;
    private static final int SHEEP_ROUTE_CLEAR_TICKS = 28;
    private static final int SHEEP_ROUTE_FAILURES_BEFORE_CLEAR = 2;
    private static final int SHEEP_ROUTE_FAILURES_BEFORE_ESCAPE = 4;
    private static final int SHEEP_UNREACHABLE_COOLDOWN_TICKS = 20 * 45;
    private static final int SHEEP_ESCAPE_REQUEST_TICKS = 20 * 8;
    private static final int SHEEP_ESCAPE_EXTRA_BLOCKS = 3;
    private static final int SHEEP_ESCAPE_MAX_BLOCKS = 10;
    private static final int SHEEP_APPROACH_SAFE_DROP_BLOCKS = 5;
    private static final int SHEEP_LOCAL_ROUTE_HORIZONTAL_RADIUS = 8;
    private static final int SHEEP_LOCAL_ROUTE_VERTICAL_DOWN = 5;
    private static final int SHEEP_LOCAL_ROUTE_VERTICAL_UP = 6;
    // Walk to the cardinal stand selected beside each source block. The old 4.5-block reach let
    // a builder remain in one spot and excavate several nearby terrain blocks before following
    // their drops, which looked unnatural and could dig a broad pit around the work site.
    private static final double BREAK_DISTANCE_SQR = 1.8D * 1.8D;
    private static final double STAND_REACHED_DISTANCE_SQR = 1.4D * 1.4D;
    private static final double SHEEP_ATTACK_DISTANCE_SQR = 2.4D * 2.4D;
    private static final double SHEEP_ROUTE_CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final Map<PlayerNpcEntity, TargetScanCache> TARGET_SCAN_CACHE = new WeakHashMap<>();
    private static final Map<Integer, List<BlockPos>> TARGET_COLUMN_OFFSETS = new HashMap<>();
    private static final Map<PlayerNpcEntity, Map<Integer, Integer>> UNREACHABLE_SHEEP_CACHE = new WeakHashMap<>();

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final ToolAi toolAi;
    private final WeaponAi weaponAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private MissingBuildMaterialNeed need;
    private BlockPos targetPos;
    private BlockPos standPos;
    private BlockPos sheepApproachPos;
    private Sheep sheepTarget;
    private int gatherTicks;
    private int repathTicks;
    private int attackTicks;
    private int sheepRouteFailures;

    public GatherMissingBuildMaterialGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.weaponAi = new WeaponAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean needsMissingBuildMaterial(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return playerNpc != null
                && playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                && hasPreparedBuildSupplies(playerNpc)
                && !TerraformBuildSiteGoal.hasActionablePrepWork(playerNpc, serverLevel)
                && PlayerNpcBuildMaterialUtil.needsNonPrimaryBuildMaterial(serverLevel, playerNpc);
    }

    public boolean hasNearbyActionableGatherTarget(ServerLevel serverLevel) {
        Optional<MissingBuildMaterialNeed> need = PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(serverLevel, this.playerNpc);
        if (need.isEmpty() || !isGatherableNeed(need.get().kind())) {
            return false;
        }
        if (need.get().kind() == MissingBuildMaterialKind.BED
                && findNearestSheep(this.playerNpc, serverLevel).isPresent()) {
            return true;
        }
        boolean actionable = this.findActionableBlockTarget(serverLevel, need.get()).isPresent();
        // Pending means the nearest-first loaded search has not proved absence yet. Yield the
        // exploration fallback until the retained cursor either finds a source or completes.
        return actionable || isTargetScanPending(this.playerNpc, need.get());
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || this.playerNpc.getGatherCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (shouldStayHomeForWeather(serverLevel)
                || !needsMissingBuildMaterial(this.playerNpc, serverLevel)) {
            return false;
        }

        Optional<MissingBuildMaterialNeed> missing = PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(serverLevel, this.playerNpc);
        if (missing.isEmpty() || !isGatherableNeed(missing.get().kind())) {
            return false;
        }

        this.need = missing.get();
        this.targetPos = null;
        this.standPos = null;
        this.sheepApproachPos = null;
        this.sheepTarget = null;
        this.sheepRouteFailures = 0;
        Optional<ActionableBlockTarget> blockTarget = this.findActionableBlockTarget(serverLevel, this.need);
        if (blockTarget.isPresent()) {
            this.targetPos = blockTarget.get().target();
            this.standPos = blockTarget.get().stand().orElse(null);
            return true;
        }
        if (this.need.kind() == MissingBuildMaterialKind.BED) {
            this.sheepTarget = findNearestSheep(this.playerNpc, serverLevel).orElse(null);
            if (this.sheepTarget != null) {
                return true;
            }
        }
        if (isTargetScanPending(this.playerNpc, this.need)) {
            this.canUseThrottle.retryIn(this.playerNpc, 1);
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return this.need != null
                && this.gatherTicks < MAX_GATHER_TICKS
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)
                && !shouldStayHomeForWeather(serverLevel)
                && needsMissingBuildMaterial(this.playerNpc, serverLevel)
                && (this.targetPos != null && this.isValidTarget(serverLevel, this.targetPos)
                || isUsableSheep(this.sheepTarget));
    }

    @Override
    public void start() {
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.attackTicks = 0;
        this.playerNpc.setCurrentAiState(this.sheepTarget == null
                ? "ai.player_npc.gathering_build_material"
                : "ai.player_npc.hunting_sheep");
        if (this.need != null && this.need.kind() == MissingBuildMaterialKind.SAND) {
            this.toolAi.equipTool(ShovelItem.class);
        } else if (this.sheepTarget != null) {
            this.weaponAi.equipBestMeleeWeapon();
        }
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.need == null) {
            return;
        }

        this.gatherTicks++;
        if (this.sheepTarget != null) {
            this.tickSheepTarget(serverLevel);
            this.updateDetail();
            return;
        }

        if (this.targetPos == null || !this.isValidTarget(serverLevel, this.targetPos)) {
            this.finishGathering();
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                35.0F,
                35.0F
        );

        if (!this.canBreakFromCurrentPosition(this.targetPos)) {
            this.breakingBlockAi.stop();
            if (this.standPos == null) {
                this.standPos = this.findStandPos(serverLevel, this.targetPos).orElse(null);
            }
            if (this.standPos == null) {
                this.finishGathering();
                return;
            }
            if (this.repathTicks-- <= 0) {
                this.pathNavigationAi.moveTo(serverLevel, this.standPos, this.speed, 3);
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateDetail();
            return;
        }

        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                this.targetPredicate(),
                this.requiredBreakTicks(),
                "gathering " + this.need.description(),
                this.need.kind() == MissingBuildMaterialKind.BED
        );
        if (result == BreakingBlockAi.TickResult.DONE) {
            this.finishGathering();
        } else if (result == BreakingBlockAi.TickResult.FAILED) {
            this.finishGathering();
        }
        this.updateDetail();
    }

    @Override
    public void stop() {
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.weaponAi.restoreMainHand();
        this.need = null;
        this.targetPos = null;
        this.standPos = null;
        this.sheepApproachPos = null;
        this.sheepTarget = null;
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.attackTicks = 0;
        this.sheepRouteFailures = 0;
        this.clearBlockAi.stop();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private void tickSheepTarget(ServerLevel serverLevel) {
        if (!isUsableSheep(this.sheepTarget)) {
            this.finishGathering();
            return;
        }

        if (this.tickSheepRouteClear(serverLevel)) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.sheepTarget, 35.0F, 35.0F);
        if (this.playerNpc.distanceToSqr(this.sheepTarget) > SHEEP_ATTACK_DISTANCE_SQR) {
            if (this.repathTicks-- <= 0) {
                if (this.moveTowardSheep(serverLevel)) {
                    this.repathTicks = REPATH_INTERVAL_TICKS;
                    return;
                }
                this.sheepRouteFailures++;
                if (this.sheepRouteFailures >= SHEEP_ROUTE_FAILURES_BEFORE_CLEAR
                        && this.requestSheepApproachEscape(serverLevel)) {
                    this.finishGathering();
                    return;
                }
                if (this.sheepRouteFailures >= SHEEP_ROUTE_FAILURES_BEFORE_CLEAR
                        && this.startClearingSheepRoute(serverLevel)) {
                    this.repathTicks = REPATH_INTERVAL_TICKS;
                    return;
                }
                if (this.sheepRouteFailures > SHEEP_ROUTE_FAILURES_BEFORE_ESCAPE) {
                    this.markSheepUnreachable(this.sheepTarget);
                    this.finishGathering(SHEEP_UNREACHABLE_COOLDOWN_TICKS);
                    return;
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.attackTicks > 0) {
            this.attackTicks--;
            return;
        }

        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        this.playerNpc.triggerMainHandAttackAnimation();
        this.playerNpc.doHurtTarget(this.sheepTarget);
        int interval = this.playerNpc.getMainHandItem().isEmpty()
                ? UNARMED_SHEEP_ATTACK_INTERVAL_TICKS
                : VanillaMeleeAttackAi.weaponAttackIntervalTicks(this.playerNpc.getMainHandItem());
        this.attackTicks = Math.max(0, interval - 1);
    }

    private boolean tickSheepRouteClear(ServerLevel serverLevel) {
        if (!this.clearBlockAi.isRunning()) {
            return false;
        }

        ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            return true;
        }
        if (result == ClearBlockAi.TickResult.DONE) {
            this.sheepRouteFailures = 0;
            this.repathTicks = 0;
            return true;
        }

        this.sheepRouteFailures++;
        this.repathTicks = 0;
        return false;
    }

    private boolean moveTowardSheep(ServerLevel serverLevel) {
        this.sheepApproachPos = this.findSheepApproachPos(serverLevel, this.sheepTarget).orElse(null);
        boolean moved = this.sheepApproachPos != null
                && this.pathNavigationAi.moveToWithLocalFallback(
                serverLevel,
                this.sheepApproachPos,
                this.speed,
                SHEEP_APPROACH_SAFE_DROP_BLOCKS,
                SHEEP_LOCAL_ROUTE_HORIZONTAL_RADIUS,
                SHEEP_LOCAL_ROUTE_VERTICAL_DOWN,
                SHEEP_LOCAL_ROUTE_VERTICAL_UP);
        if (!moved) {
            BlockPos sheepPos = this.sheepTarget.blockPosition();
            Path directPath = this.playerNpc.getNavigation().createPath(this.sheepTarget, 0);
            boolean validDirectPath = directPath != null
                    && directPath.canReach()
                    && (this.pathNavigationAi.isValidPathTo(sheepPos, directPath)
                    || (this.sheepApproachPos != null
                    && this.pathNavigationAi.isValidPathTo(this.sheepApproachPos, directPath)));
            if (validDirectPath) {
                moved = this.playerNpc.getNavigation().moveTo(directPath, this.speed);
            } else {
                this.playerNpc.getNavigation().stop();
            }
        }
        if (moved) {
            this.sheepRouteFailures = 0;
        }
        return moved;
    }

    private Optional<BlockPos> findSheepApproachPos(ServerLevel serverLevel, Sheep sheep) {
        if (sheep == null) {
            return Optional.empty();
        }

        BlockPos sheepFeet = sheep.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx * dx + dz * dz > 4) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos candidate = sheepFeet.offset(dx, dy, dz);
                    if (PathNavigationAi.canStandAt(serverLevel, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }

        return candidates.stream()
                .min(Comparator
                        .comparingDouble((BlockPos pos) -> this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D))
                        .thenComparingDouble(pos -> blockDistanceSqr(pos, sheepFeet)));
    }

    private boolean startClearingSheepRoute(ServerLevel serverLevel) {
        if (this.sheepTarget == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos target = this.sheepApproachPos == null ? this.sheepTarget.blockPosition() : this.sheepApproachPos;
        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(feet, target, target));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos step = feet.relative(direction);
            candidates.add(step);
            candidates.add(step.above());
            candidates.add(step.above(2));
            candidates.add(step.below());
        }
        candidates.removeIf(pos -> pos == null || isProtectedHomeBlock(this.playerNpc, pos));

        return this.clearBlockAi.startNearest(
                serverLevel,
                candidates,
                this::isClearableRouteBlock,
                "clearing sheep path",
                SHEEP_ROUTE_CLEAR_TICKS,
                SHEEP_ROUTE_CLEAR_DISTANCE_SQR,
                true
        );
    }

    private boolean requestSheepApproachEscape(ServerLevel serverLevel) {
        if (this.sheepTarget == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos target = this.sheepTarget.blockPosition();
        if (target.getY() <= feet.getY() + 1 || serverLevel.canSeeSky(feet.above())) {
            return false;
        }

        int maxPillarBlocks = Math.min(
                SHEEP_ESCAPE_MAX_BLOCKS,
                Math.max(1, target.getY() - feet.getY() + SHEEP_ESCAPE_EXTRA_BLOCKS)
        );
        this.playerNpc.requestExplorationUpwardEscapeTo(target, SHEEP_ESCAPE_REQUEST_TICKS, maxPillarBlocks);
        return true;
    }

    private boolean isClearableRouteBlock(BlockState state) {
        return state != null && !state.isAir();
    }

    private void finishGathering() {
        this.finishGathering(20 + this.playerNpc.getRandom().nextInt(20));
    }

    private void finishGathering(int cooldownTicks) {
        this.playerNpc.setGatherCooldown(cooldownTicks);
        this.targetPos = null;
        this.standPos = null;
        this.sheepApproachPos = null;
        this.sheepTarget = null;
        this.sheepRouteFailures = 0;
        this.clearBlockAi.stop();
    }

    private void markSheepUnreachable(Sheep sheep) {
        if (sheep == null) {
            return;
        }
        UNREACHABLE_SHEEP_CACHE
                .computeIfAbsent(this.playerNpc, ignored -> new HashMap<>())
                .put(sheep.getId(), this.playerNpc.tickCount + SHEEP_UNREACHABLE_COOLDOWN_TICKS);
        this.playerNpc.setHuntSheepCooldown(Math.max(this.playerNpc.getHuntSheepCooldown(), SHEEP_UNREACHABLE_COOLDOWN_TICKS));
    }

    private Optional<BlockPos> findStandPos(ServerLevel serverLevel, BlockPos target) {
        return findStandPos(this.playerNpc, this.pathNavigationAi, serverLevel, target);
    }

    private static Optional<BlockPos> findStandPos(
            PlayerNpcEntity playerNpc,
            PathNavigationAi pathNavigationAi,
            ServerLevel serverLevel,
            BlockPos target
    ) {
        return Direction.Plane.HORIZONTAL.stream()
                .map(target::relative)
                .filter(pos -> PathNavigationAi.canStandAt(serverLevel, pos))
                .filter(pos -> {
                    Path path = playerNpc.getNavigation().createPath(pos, 0);
                    return pathNavigationAi.isValidPathTo(pos, path)
                            || playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= STAND_REACHED_DISTANCE_SQR;
                })
                .min(Comparator.comparingDouble(pos -> playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D)))
                .map(BlockPos::immutable);
    }

    private boolean canBreakFromCurrentPosition(BlockPos pos) {
        return canBreakFromCurrentPosition(this.playerNpc, pos);
    }

    private static boolean canBreakFromCurrentPosition(PlayerNpcEntity playerNpc, BlockPos pos) {
        return playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= BREAK_DISTANCE_SQR;
    }

    private boolean isValidTarget(ServerLevel serverLevel, BlockPos pos) {
        return this.targetPredicate().test(serverLevel.getBlockState(pos));
    }

    private Optional<ActionableBlockTarget> findActionableBlockTarget(
            ServerLevel serverLevel,
            MissingBuildMaterialNeed materialNeed
    ) {
        if (materialNeed.kind() == MissingBuildMaterialKind.BED) {
            return this.findActionableBedTarget(serverLevel, materialNeed);
        }
        return findTargetBlock(this.playerNpc, serverLevel, materialNeed)
                .flatMap(pos -> this.resolveActionableBlockTarget(serverLevel, pos));
    }

    private Optional<ActionableBlockTarget> findActionableBedTarget(
            ServerLevel serverLevel,
            MissingBuildMaterialNeed materialNeed
    ) {
        return findTargetBlock(this.playerNpc, serverLevel, materialNeed)
                .flatMap(pos -> this.resolveActionableBlockTarget(serverLevel, pos));
    }

    private Optional<ActionableBlockTarget> resolveActionableBlockTarget(ServerLevel serverLevel, BlockPos target) {
        if (this.canBreakFromCurrentPosition(target)) {
            return Optional.of(new ActionableBlockTarget(target.immutable(), Optional.empty()));
        }
        return this.findStandPos(serverLevel, target)
                .map(stand -> new ActionableBlockTarget(target.immutable(), Optional.of(stand)));
    }

    private Predicate<BlockState> targetPredicate() {
        MissingBuildMaterialKind kind = this.need == null ? MissingBuildMaterialKind.NONE : this.need.kind();
        return state -> switch (kind) {
            case SAND -> PlayerNpcBuildMaterialUtil.isSandSourceBlock(state);
            case PLANT -> PlayerNpcBuildMaterialUtil.isGatherablePlantBlock(state);
            case BED -> state.getBlock() instanceof BedBlock;
            case OTHER -> this.need != null && PlayerNpcBuildMaterialUtil.matches(state, this.need.targetState());
            default -> false;
        };
    }

    private int requiredBreakTicks() {
        if (this.need == null) {
            return 20;
        }
        return switch (this.need.kind()) {
            case PLANT -> 8;
            case BED -> 35;
            case SAND -> 36;
            default -> 25;
        };
    }

    private void updateDetail() {
        if (this.need == null) {
            return;
        }
        if (this.clearBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.clearBlockAi.detail());
            return;
        }
        if (this.sheepTarget != null) {
            int woolCount = PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(ItemTags.WOOL));
            this.playerNpc.setCurrentAiDetail("hunting sheep for " + this.need.description() + " wool=" + woolCount + "/3");
            return;
        }
        if (this.breakingBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.breakingBlockAi.detail());
            return;
        }
        if (this.targetPos != null) {
            this.playerNpc.setCurrentAiDetail("seeking " + this.need.description() + " @ "
                    + this.targetPos.getX() + " "
                    + this.targetPos.getY() + " "
                    + this.targetPos.getZ());
        }
    }

    private static boolean hasPreparedBuildSupplies(PlayerNpcEntity playerNpc) {
        return !playerNpc.shouldPrioritizeLogGathering()
                && !playerNpc.shouldPrioritizeCobblestoneGathering();
    }

    private static boolean isGatherableNeed(MissingBuildMaterialKind kind) {
        return kind == MissingBuildMaterialKind.SAND
                || kind == MissingBuildMaterialKind.PLANT
                || kind == MissingBuildMaterialKind.BED
                || kind == MissingBuildMaterialKind.OTHER;
    }

    private static Optional<BlockPos> findTargetBlock(PlayerNpcEntity playerNpc, ServerLevel serverLevel, MissingBuildMaterialNeed need) {
        BlockPos center = playerNpc.blockPosition();
        TargetScanCache cache = TARGET_SCAN_CACHE.get(playerNpc);
        if (cache != null && cache.matches(playerNpc.tickCount, center, need)) {
            if (cache.complete()) {
                Optional<BlockPos> cached = cache.target();
                if (cached.isEmpty() || isValidTargetBlock(playerNpc, serverLevel, cached.get(), need)) {
                    return cached;
                }
            }
        } else {
            cache = null;
        }

        int radius = cache == null
                ? PlayerNpcAdaptiveSearchScope.buildMaterialCoverageRadius(serverLevel)
                : cache.searchRadius();
        List<BlockPos> columnOffsets = targetColumnOffsets(radius);
        int startColumnIndex = cache == null ? 0 : cache.nextColumnIndex();
        int endColumnIndex = Math.min(
                columnOffsets.size(),
                startColumnIndex + MAX_TARGET_SCAN_COLUMNS_PER_SLICE
        );
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos bestPos = cache == null ? null : cache.target().orElse(null);
        double bestDistance = bestPos == null ? Double.MAX_VALUE : center.distSqr(bestPos);
        int minY = center.getY() - 8;
        int maxY = center.getY() + 8;
        for (int columnIndex = startColumnIndex; columnIndex < endColumnIndex; columnIndex++) {
            BlockPos offset = columnOffsets.get(columnIndex);
            int x = center.getX() + offset.getX();
            int z = center.getZ() + offset.getZ();
            BlockPos column = new BlockPos(x, center.getY(), z);
            if (!serverLevel.hasChunkAt(column)) {
                continue;
            }
            for (int y = minY; y <= maxY; y++) {
                cursor.set(x, y, z);
                BlockPos target = matchingTargetPos(serverLevel, cursor, need);
                if (target == null || isProtectedHomeBlock(playerNpc, target)) {
                    continue;
                }
                double distance = playerNpc.distanceToSqr(
                        target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D
                );
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestPos = target.immutable();
                }
            }
        }

        // Columns are nearest-first. A valid source in the current slice is immediately
        // actionable; only negative absence requires the complete retained pass.
        boolean complete = bestPos != null || endColumnIndex >= columnOffsets.size();
        Optional<BlockPos> result = Optional.ofNullable(bestPos);
        TARGET_SCAN_CACHE.put(playerNpc, new TargetScanCache(
                playerNpc.tickCount,
                center.immutable(),
                need.kind(),
                need.targetState(),
                result,
                radius,
                complete ? 0 : endColumnIndex,
                complete
        ));
        return complete ? result : Optional.empty();
    }

    private static boolean isTargetScanPending(
            PlayerNpcEntity playerNpc,
            MissingBuildMaterialNeed need
    ) {
        TargetScanCache cache = TARGET_SCAN_CACHE.get(playerNpc);
        return cache != null
                && !cache.complete()
                && cache.kind() == need.kind()
                && cache.targetState().equals(need.targetState());
    }

    private static List<BlockPos> targetColumnOffsets(int radius) {
        synchronized (TARGET_COLUMN_OFFSETS) {
            return TARGET_COLUMN_OFFSETS.computeIfAbsent(radius, value -> {
                List<BlockPos> offsets = new ArrayList<>((value * 2 + 1) * (value * 2 + 1));
                for (int dx = -value; dx <= value; dx++) {
                    for (int dz = -value; dz <= value; dz++) {
                        offsets.add(new BlockPos(dx, 0, dz));
                    }
                }
                offsets.sort(Comparator
                        .comparingInt((BlockPos pos) -> pos.getX() * pos.getX() + pos.getZ() * pos.getZ())
                        .thenComparingInt(BlockPos::getX)
                        .thenComparingInt(BlockPos::getZ));
                return List.copyOf(offsets);
            });
        }
    }

    private static boolean matchesNeed(BlockState state, MissingBuildMaterialNeed need) {
        return switch (need.kind()) {
            case SAND -> PlayerNpcBuildMaterialUtil.isSandSourceBlock(state);
            case PLANT -> PlayerNpcBuildMaterialUtil.isGatherablePlantBlock(state);
            case BED -> state.getBlock() instanceof BedBlock;
            case OTHER -> PlayerNpcBuildMaterialUtil.matches(state, need.targetState());
            default -> false;
        };
    }

    private static Optional<Sheep> findNearestSheep(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        pruneUnreachableSheep(playerNpc);
        return serverLevel.getEntitiesOfClass(
                        Sheep.class,
                        playerNpc.getBoundingBox().inflate(SEARCH_RADIUS),
                        sheep -> isUsableSheep(sheep) && !isUnreachableSheep(playerNpc, sheep))
                .stream()
                .min(Comparator.comparingDouble(playerNpc::distanceToSqr));
    }

    private static boolean isUsableSheep(Sheep sheep) {
        return sheep != null && sheep.isAlive() && !sheep.isBaby() && !sheep.isSheared();
    }

    private static boolean isUnreachableSheep(PlayerNpcEntity playerNpc, Sheep sheep) {
        Map<Integer, Integer> skipped = UNREACHABLE_SHEEP_CACHE.get(playerNpc);
        if (skipped == null) {
            return false;
        }
        return skipped.getOrDefault(sheep.getId(), 0) > playerNpc.tickCount;
    }

    private static void pruneUnreachableSheep(PlayerNpcEntity playerNpc) {
        Map<Integer, Integer> skipped = UNREACHABLE_SHEEP_CACHE.get(playerNpc);
        if (skipped == null) {
            return;
        }
        skipped.entrySet().removeIf(entry -> entry.getValue() <= playerNpc.tickCount);
        if (skipped.isEmpty()) {
            UNREACHABLE_SHEEP_CACHE.remove(playerNpc);
        }
    }

    private static boolean isProtectedHomeBlock(PlayerNpcEntity playerNpc, BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos)
                || pos.equals(playerNpc.blockPosition().below());
    }

    private static boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return serverLevel.isNight() || serverLevel.isThundering();
    }

    private static BlockPos matchingTargetPos(ServerLevel serverLevel, BlockPos pos, MissingBuildMaterialNeed need) {
        BlockState state = serverLevel.getBlockState(pos);
        if (!matchesNeed(state, need)) {
            return null;
        }
        if (need.kind() == MissingBuildMaterialKind.BED) {
            return normalizeBedFoot(serverLevel, pos, state);
        }
        return pos.immutable();
    }

    private static BlockPos normalizeBedFoot(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BedBlock)
                || !state.hasProperty(BedBlock.PART)
                || !state.hasProperty(BedBlock.FACING)) {
            return pos.immutable();
        }
        if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.immutable();
        }

        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos foot = pos.relative(facing.getOpposite());
        if (!serverLevel.hasChunkAt(foot)) {
            return pos.immutable();
        }
        BlockState footState = serverLevel.getBlockState(foot);
        if (footState.getBlock() instanceof BedBlock
                && footState.hasProperty(BedBlock.PART)
                && footState.getValue(BedBlock.PART) == BedPart.FOOT) {
            return foot.immutable();
        }
        return pos.immutable();
    }

    private static boolean isValidTargetBlock(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos, MissingBuildMaterialNeed need) {
        return !isProtectedHomeBlock(playerNpc, pos)
                && matchesNeed(serverLevel.getBlockState(pos), need);
    }

    private static double blockDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private record TargetScanCache(
            int tick,
            BlockPos scanCenter,
            MissingBuildMaterialKind kind,
            BlockState targetState,
            Optional<BlockPos> target,
            int searchRadius,
            int nextColumnIndex,
            boolean complete) {
        private boolean matches(int currentTick, BlockPos currentCenter, MissingBuildMaterialNeed need) {
            return (!this.complete || currentTick - this.tick <= TARGET_SCAN_CACHE_TICKS)
                    && blockDistanceSqr(this.scanCenter, currentCenter) <= 4.0D
                    && this.kind == need.kind()
                    && this.targetState.equals(need.targetState());
        }
    }

    private record ActionableBlockTarget(BlockPos target, Optional<BlockPos> stand) {
    }
}
