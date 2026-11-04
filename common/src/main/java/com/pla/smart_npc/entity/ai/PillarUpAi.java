package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcCollisionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PillarUpAi {
    public enum TickResult {
        IDLE,
        RUNNING,
        PLACED,
        FAILED
    }

    private static final int JUMP_WINDUP_TICKS = 2;
    private static final int MAX_PLACE_WAIT_TICKS = 32;
    private static final double CENTER_EPSILON = 0.05D;
    private static final double CENTER_BLOCKER_PADDING = 0.04D;
    private static final int COLLISION_BLOCKER_FAIL_TICKS = 8;
    private static final int PILLAR_SETTLE_TICKS = 2;
    private static final int MAX_SETTLE_WAIT_TICKS = 30;

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final PlacingBlockAi placingBlockAi;
    private final ItemLike blockItem;
    private final BlockState placeState;
    private final boolean gatherLogsOwnedSupports;
    private BlockPos placePos;
    private BlockPos lastPlacedPos;
    private BlockPos lastFailureBlockerPos;
    private BlockPos observedJumpBlockerPos;
    private BlockPos settlingSupportPos;
    private String lastFailureDetail = "";
    private int jumpDelayTicks;
    private int placeWaitTicks;
    private int settleTicks;
    private int settleWaitTicks;
    private final Set<BlockPos> placedSupports = new LinkedHashSet<>();

    public PillarUpAi(PlayerNpcEntity playerNpc, ToolAi toolAi, ItemLike blockItem, BlockState placeState) {
        this(playerNpc, toolAi, blockItem, placeState, false);
    }

    public PillarUpAi(
            PlayerNpcEntity playerNpc,
            ToolAi toolAi,
            ItemLike blockItem,
            BlockState placeState,
            boolean gatherLogsOwnedSupports
    ) {
        this.playerNpc = playerNpc;
        this.toolAi = toolAi;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.blockItem = blockItem;
        this.placeState = placeState;
        this.gatherLogsOwnedSupports = gatherLogsOwnedSupports;
    }

    public boolean isRunning() {
        return this.placePos != null || this.settlingSupportPos != null;
    }

    public boolean canStart(ServerLevel serverLevel, BlockPos feet) {
        return this.startBlocker(serverLevel, feet).isBlank();
    }

    public String startBlocker(ServerLevel serverLevel, BlockPos feet) {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            return "worker slot not held";
        }
        if (!this.playerNpc.onGround()) {
            return "not on ground";
        }
        if (!serverLevel.isInWorldBounds(feet) || !serverLevel.getWorldBorder().isWithinBounds(feet)) {
            return "feet out of bounds " + posText(feet);
        }
        if (!this.hasBlock()) {
            return "missing block item " + this.blockItem.asItem().getDescriptionId();
        }

        BlockPos missingSupport = this.findMissingTemporarySupportLink(serverLevel, feet);
        if (missingSupport != null) {
            return "missing pillar support " + posText(missingSupport);
        }

        String spaceBlocker = this.pillarSpaceBlocker(serverLevel, feet);
        if (!spaceBlocker.isBlank()) {
            return spaceBlocker;
        }
        String entityBlocker = this.entityBlockerText(serverLevel, feet);
        if (!entityBlocker.isBlank()) {
            return entityBlocker;
        }
        return "";
    }

    public BlockPos startBlockerPos(ServerLevel serverLevel, BlockPos feet) {
        if (feet == null) {
            return null;
        }
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            return null;
        }
        if (!this.playerNpc.onGround()
                || !serverLevel.isInWorldBounds(feet)
                || !serverLevel.getWorldBorder().isWithinBounds(feet)) {
            return feet.immutable();
        }
        if (!this.hasBlock()) {
            return null;
        }

        BlockPos spaceBlocker = this.pillarSpaceBlockerPos(serverLevel, feet);
        if (spaceBlocker != null) {
            return spaceBlocker;
        }
        if (!this.entityBlockerText(serverLevel, feet).isBlank()) {
            return feet.immutable();
        }
        return null;
    }

    public boolean start(ServerLevel serverLevel, BlockPos feet) {
        if (!this.playerNpc.onGround() || !this.canStart(serverLevel, feet) || !this.toolAi.equipItem(this.blockItem)) {
            return false;
        }

        if (!this.placedSupports.contains(feet.below())) {
            this.placedSupports.clear();
        }
        BlockPos missingSupport = this.findMissingPlacedSupport(serverLevel, feet);
        if (missingSupport != null) {
            this.lastFailureBlockerPos = missingSupport.immutable();
            this.lastFailureDetail = "missing pillar support @ " + posText(missingSupport);
            return false;
        }

        this.placePos = feet.immutable();
        this.lastPlacedPos = null;
        this.lastFailureBlockerPos = null;
        this.observedJumpBlockerPos = null;
        this.lastFailureDetail = "";
        this.jumpDelayTicks = JUMP_WINDUP_TICKS;
        this.placingBlockAi.resetDelay();
        this.placeWaitTicks = 0;
        this.settleTicks = 0;
        this.settleWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.lookDownAt(this.placePos);
        return true;
    }

    public TickResult tick(ServerLevel serverLevel) {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            // Keep the pending placement/settlement coordinates intact. The owning goal may be
            // resumed by a later worker holder, but this non-holder must not jump, place, equip,
            // or continue navigation in the meantime.
            this.playerNpc.getNavigation().stop();
            return this.isRunning() ? TickResult.RUNNING : TickResult.IDLE;
        }
        if (this.settlingSupportPos != null) {
            return this.tickSupportSettlement(serverLevel);
        }
        if (this.placePos == null) {
            return TickResult.IDLE;
        }

        BlockPos missingSupport = this.findMissingPlacedSupport(serverLevel, this.playerNpc.blockPosition());
        if (missingSupport != null) {
            return this.fail("missing pillar support @ " + posText(missingSupport), missingSupport);
        }

        if (this.jumpDelayTicks > 0) {
            if (!this.toolAi.equipItem(this.blockItem)) {
                return this.fail("missing block item during pillar", null);
            }
            if (!this.centerOnPillarBase(serverLevel, this.placePos)) {
                this.lookAtFailureBlocker();
                return TickResult.FAILED;
            }
            this.playerNpc.getNavigation().stop();
            this.lookDownAt(this.placePos);
            if (this.jumpDelayTicks == 1
                    && this.placingBlockAi.tickDelay(PlacingBlockAi.PILLAR_PLACE_DELAY)) {
                return TickResult.RUNNING;
            }
            this.jumpDelayTicks--;
            if (this.jumpDelayTicks <= 0) {
                this.playerNpc.shortPillarJump();
            }
            return TickResult.RUNNING;
        }

        this.rememberJumpBlocker(serverLevel);
        if (this.tryAcceptOccupiedPillarSupport(serverLevel)) {
            return TickResult.RUNNING;
        }

        this.placeWaitTicks++;
        if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
            BlockPos blocker = this.bestFailureBlocker(serverLevel);
            return this.fail("pillar clearance timeout", blocker);
        }

        if (!this.hasPlacementClearance(serverLevel, this.placePos, this.placeState)) {
            BlockPos blocker = this.bestFailureBlocker(serverLevel);
            boolean finishedBlockedJump = this.observedJumpBlockerPos != null
                    && this.playerNpc.getDeltaMovement().y <= 0.05D;
            if (blocker != null
                    && (finishedBlockedJump || this.placeWaitTicks >= COLLISION_BLOCKER_FAIL_TICKS)) {
                return this.fail("pillar jump blocked by " + blockText(serverLevel.getBlockState(blocker)), blocker);
            }
            this.lookDownAt(this.placePos);
            return TickResult.RUNNING;
        }

        if (!serverLevel.getBlockState(this.placePos).canBeReplaced()) {
            if (this.tryAcceptOccupiedPillarSupport(serverLevel)) {
                return TickResult.RUNNING;
            }
            return this.fail("pillar base no longer replaceable", this.placePos);
        }
        if (!this.toolAi.equipItem(this.blockItem)) {
            return this.fail("missing block item before place", null);
        }
        if (!this.canPlaceWithoutClipping(serverLevel, this.placePos, this.placeState)) {
            return this.fail("pillar place clipping", this.findCurrentCollisionBlocker(serverLevel));
        }
        if (!this.consumeBlock()) {
            return this.fail("failed to consume pillar block", null);
        }

        if (!this.placingBlockAi.placeBlock(serverLevel, this.placePos, this.placeState)) {
            InventoryUtils.addItem(this.playerNpc.getInventory(), new ItemStack(this.blockItem));
            return this.fail("failed to set pillar block", this.placePos);
        }

        BlockPos placedSupport = this.placePos.immutable();
        if (!this.isStablePillarSupport(serverLevel, placedSupport)) {
            return this.fail("placed pillar support did not remain solid", placedSupport);
        }
        if (this.gatherLogsOwnedSupports) {
            this.playerNpc.markGatherLogsTemporaryPillarSupport(placedSupport);
        } else {
            this.playerNpc.markTemporaryPillarSupport(placedSupport);
        }
        this.placedSupports.add(placedSupport);
        this.beginSupportSettlement(placedSupport);
        return TickResult.RUNNING;
    }

    public BlockPos consumeLastPlacedPos() {
        BlockPos placed = this.lastPlacedPos;
        this.lastPlacedPos = null;
        return placed == null ? null : placed.immutable();
    }

    public BlockPos consumeLastFailureBlockerPos() {
        BlockPos blocker = this.lastFailureBlockerPos;
        this.lastFailureBlockerPos = null;
        return blocker == null ? null : blocker.immutable();
    }

    public String consumeLastFailureDetail() {
        String detail = this.lastFailureDetail;
        this.lastFailureDetail = "";
        return detail;
    }

    public void clear() {
        this.placePos = null;
        this.observedJumpBlockerPos = null;
        this.settlingSupportPos = null;
        this.jumpDelayTicks = 0;
        this.placingBlockAi.resetDelay();
        this.placeWaitTicks = 0;
        this.settleTicks = 0;
        this.settleWaitTicks = 0;
        this.placedSupports.clear();
    }

    public String detail() {
        BlockPos activePos = this.placePos == null ? this.settlingSupportPos : this.placePos;
        if (activePos == null) {
            return "";
        }
        return (this.settlingSupportPos == null ? "pillaring up @ " : "settling on pillar @ ")
                + activePos.getX() + " "
                + activePos.getY() + " "
                + activePos.getZ();
    }

    public boolean hasPillarSpace(ServerLevel serverLevel, BlockPos feet) {
        return this.pillarSpaceBlocker(serverLevel, feet).isBlank();
    }

    private String pillarSpaceBlocker(ServerLevel serverLevel, BlockPos feet) {
        BlockState feetState = serverLevel.getBlockState(feet);
        BlockState headState = serverLevel.getBlockState(feet.above());
        BlockState jumpHeadroomState = serverLevel.getBlockState(feet.above(2));
        if (!feetState.canBeReplaced()) {
            return "feet not replaceable " + blockText(feetState) + " " + posText(feet);
        }
        if (!feetState.getCollisionShape(serverLevel, feet).isEmpty()) {
            return "feet collision " + blockText(feetState) + " " + posText(feet);
        }
        if (!headState.getCollisionShape(serverLevel, feet.above()).isEmpty()) {
            return "head collision " + blockText(headState) + " " + posText(feet.above());
        }
        if (!jumpHeadroomState.getCollisionShape(serverLevel, feet.above(2)).isEmpty()) {
            return "jump collision " + blockText(jumpHeadroomState) + " " + posText(feet.above(2));
        }
        if (!feetState.getFluidState().isEmpty()) {
            return "feet fluid " + posText(feet);
        }
        if (!headState.getFluidState().isEmpty()) {
            return "head fluid " + posText(feet.above());
        }
        if (!jumpHeadroomState.getFluidState().isEmpty()) {
            return "jump fluid " + posText(feet.above(2));
        }
        return "";
    }

    private BlockPos pillarSpaceBlockerPos(ServerLevel serverLevel, BlockPos feet) {
        BlockState feetState = serverLevel.getBlockState(feet);
        BlockState headState = serverLevel.getBlockState(feet.above());
        BlockState jumpHeadroomState = serverLevel.getBlockState(feet.above(2));
        if (!feetState.canBeReplaced()
                || !feetState.getCollisionShape(serverLevel, feet).isEmpty()
                || !feetState.getFluidState().isEmpty()) {
            return feet.immutable();
        }
        if (!headState.getCollisionShape(serverLevel, feet.above()).isEmpty()
                || !headState.getFluidState().isEmpty()) {
            return feet.above().immutable();
        }
        if (!jumpHeadroomState.getCollisionShape(serverLevel, feet.above(2)).isEmpty()
                || !jumpHeadroomState.getFluidState().isEmpty()) {
            return feet.above(2).immutable();
        }
        return null;
    }

    private boolean hasBlock() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (mainHand.is(this.blockItem.asItem())) {
            return true;
        }
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(this.blockItem.asItem())) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeBlock() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (mainHand.is(this.blockItem.asItem())) {
            mainHand.shrink(1);
            return true;
        }
        return this.playerNpc.consumeInventoryItem(this.blockItem, 1).isPresent();
    }

    private boolean hasPlacementClearance(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (pos == null || this.playerNpc.onGround()) {
            return false;
        }
        return state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .noneMatch(box -> box.intersects(this.playerNpc.getBoundingBox().inflate(0.02D)));
    }

    private boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (this.hasOtherEntityInBlock(serverLevel, pos)) {
            return false;
        }

        List<AABB> boxes = state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
        return boxes.stream().noneMatch(box -> box.intersects(this.playerNpc.getBoundingBox().inflate(0.02D)));
    }

    private boolean tryAcceptOccupiedPillarSupport(ServerLevel serverLevel) {
        if (this.placePos == null) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(this.placePos);
        if (state.canBeReplaced()
                || state.getCollisionShape(serverLevel, this.placePos).isEmpty()
                || !state.getFluidState().isEmpty()
                || serverLevel.getBlockEntity(this.placePos) != null) {
            return false;
        }

        if (!this.isStandingOnPillarSupport(serverLevel, this.placePos)) {
            return false;
        }

        BlockPos occupiedSupport = this.placePos.immutable();
        this.placedSupports.add(occupiedSupport);
        this.beginSupportSettlement(occupiedSupport);
        return true;
    }

    private boolean centerOnPillarBase(ServerLevel serverLevel, BlockPos pos) {
        double targetX = pos.getX() + 0.5D;
        double targetZ = pos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        if (Math.abs(dx) <= CENTER_EPSILON && Math.abs(dz) <= CENTER_EPSILON) {
            return true;
        }

        AABB centeredBox = this.playerNpc.getBoundingBox().move(dx, 0.0D, dz);
        BlockPos blocker = this.findCollisionBlocker(serverLevel, centeredBox, CENTER_BLOCKER_PADDING, 0.12D);
        if (blocker != null || !PlayerNpcCollisionUtil.noBlockingCollision(serverLevel, this.playerNpc, centeredBox)) {
            this.lastFailureBlockerPos = blocker == null ? null : blocker.immutable();
            this.lastFailureDetail = "pillar center blocked"
                    + (blocker == null ? "" : " by " + blockText(serverLevel.getBlockState(blocker)) + " @ " + posText(blocker));
            this.clear();
            return false;
        }

        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setPos(targetX, this.playerNpc.getY(), targetZ);
        this.playerNpc.setDeltaMovement(0.0D, motion.y, 0.0D);
        return true;
    }

    private TickResult fail(String detail, BlockPos blockerPos) {
        if (blockerPos == null) {
            blockerPos = this.observedJumpBlockerPos;
        }
        this.lastFailureDetail = detail == null ? "pillar failed" : detail;
        this.lastFailureBlockerPos = blockerPos == null ? null : blockerPos.immutable();
        this.lookAtFailureBlocker();
        this.clear();
        return TickResult.FAILED;
    }

    private BlockPos findCurrentCollisionBlocker(ServerLevel serverLevel) {
        return this.findCollisionBlocker(serverLevel, this.playerNpc.getBoundingBox(), 0.08D, 0.35D);
    }

    /**
     * Remember a ceiling hit throughout the airborne placement window. After the NPC falls, the
     * live bounding box no longer intersects that block and callers would otherwise receive a null
     * failure target and be unable to clear the obstruction.
     */
    private void rememberJumpBlocker(ServerLevel serverLevel) {
        BlockPos blocker = this.findCurrentOverheadCollisionBlocker(serverLevel);
        if (blocker != null && this.placePos != null && blocker.getY() > this.placePos.getY()) {
            this.observedJumpBlockerPos = blocker.immutable();
        }
    }

    private BlockPos bestFailureBlocker(ServerLevel serverLevel) {
        if (this.observedJumpBlockerPos != null) {
            BlockState observedState = serverLevel.getBlockState(this.observedJumpBlockerPos);
            if (!observedState.getCollisionShape(serverLevel, this.observedJumpBlockerPos).isEmpty()) {
                return this.observedJumpBlockerPos.immutable();
            }
            this.observedJumpBlockerPos = null;
        }
        return this.findCurrentCollisionBlocker(serverLevel);
    }

    private BlockPos findCurrentOverheadCollisionBlocker(ServerLevel serverLevel) {
        AABB box = this.playerNpc.getBoundingBox();
        AABB topSweep = new AABB(
                box.minX + 0.02D,
                Math.max(box.minY + 0.5D, box.maxY - 0.12D),
                box.minZ + 0.02D,
                box.maxX - 0.02D,
                box.maxY + 0.35D,
                box.maxZ - 0.02D
        );
        return this.findCollisionBlocker(serverLevel, topSweep);
    }

    private BlockPos findCollisionBlocker(ServerLevel serverLevel, AABB box, double horizontalPadding, double topPadding) {
        AABB checkBox = new AABB(
                box.minX - horizontalPadding,
                box.minY + 0.05D,
                box.minZ - horizontalPadding,
                box.maxX + horizontalPadding,
                box.maxY + topPadding,
                box.maxZ + horizontalPadding
        );

        return this.findCollisionBlocker(serverLevel, checkBox);
    }

    private BlockPos findCollisionBlocker(ServerLevel serverLevel, AABB checkBox) {
        int minX = Mth.floor(checkBox.minX);
        int minY = Mth.floor(checkBox.minY);
        int minZ = Mth.floor(checkBox.minZ);
        int maxX = Mth.floor(checkBox.maxX);
        int maxY = Mth.floor(checkBox.maxY);
        int maxZ = Mth.floor(checkBox.maxZ);
        for (BlockPos mutable : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockPos pos = mutable.immutable();
            BlockState state = serverLevel.getBlockState(pos);
            if (state.getCollisionShape(serverLevel, pos).isEmpty()) {
                continue;
            }
            for (AABB collisionBox : state.getCollisionShape(serverLevel, pos).toAabbs()) {
                if (collisionBox.move(pos).intersects(checkBox)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private void beginSupportSettlement(BlockPos supportPos) {
        this.placePos = null;
        this.observedJumpBlockerPos = null;
        this.jumpDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.placingBlockAi.resetDelay();
        this.settlingSupportPos = supportPos.immutable();
        this.settleTicks = 0;
        this.settleWaitTicks = 0;
    }

    private TickResult tickSupportSettlement(ServerLevel serverLevel) {
        BlockPos supportPos = this.settlingSupportPos;
        if (supportPos == null) {
            return TickResult.IDLE;
        }

        BlockPos missingSupport = this.findMissingPlacedSupport(serverLevel, this.playerNpc.blockPosition());
        if (missingSupport != null) {
            return this.fail("missing pillar support @ " + posText(missingSupport), missingSupport);
        }

        this.playerNpc.getNavigation().stop();
        this.lookDownAt(supportPos);
        this.settleWaitTicks++;
        if (!this.isStandingOnPillarSupport(serverLevel, supportPos)) {
            this.settleTicks = 0;
            if (this.settleWaitTicks > MAX_SETTLE_WAIT_TICKS) {
                return this.fail("did not land on placed pillar support", supportPos);
            }
            return TickResult.RUNNING;
        }

        if (this.settleTicks <= 0) {
            this.settleTicks = PILLAR_SETTLE_TICKS;
        }
        this.settleTicks--;
        if (this.settleTicks > 0) {
            return TickResult.RUNNING;
        }

        this.lastPlacedPos = supportPos.immutable();
        this.settlingSupportPos = null;
        this.settleWaitTicks = 0;
        return TickResult.PLACED;
    }

    private boolean isStandingOnPillarSupport(ServerLevel serverLevel, BlockPos supportPos) {
        if (!this.playerNpc.onGround() || !this.isStablePillarSupport(serverLevel, supportPos)) {
            return false;
        }
        if (!this.playerNpc.blockPosition().below().equals(supportPos)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(supportPos);
        double supportTop = supportPos.getY() + state.getCollisionShape(serverLevel, supportPos).bounds().maxY;
        return Math.abs(this.playerNpc.getBoundingBox().minY - supportTop) <= 0.12D;
    }

    private boolean isStablePillarSupport(ServerLevel serverLevel, BlockPos supportPos) {
        if (supportPos == null
                || !serverLevel.isInWorldBounds(supportPos)
                || !serverLevel.getWorldBorder().isWithinBounds(supportPos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(supportPos);
        return !state.canBeReplaced()
                && !state.getCollisionShape(serverLevel, supportPos).isEmpty()
                && state.getFluidState().isEmpty();
    }

    private BlockPos findMissingPlacedSupport(ServerLevel serverLevel, BlockPos feet) {
        for (BlockPos supportPos : this.placedSupports) {
            if (!this.isStablePillarSupport(serverLevel, supportPos)) {
                return supportPos.immutable();
            }
        }
        return this.findMissingTemporarySupportLink(serverLevel, feet);
    }

    private BlockPos findMissingTemporarySupportLink(ServerLevel serverLevel, BlockPos feet) {
        BlockPos cursor = feet.below();
        int checked = 0;
        while (this.playerNpc.isTemporaryPillarSupport(cursor) && checked++ < 128) {
            if (!this.isStablePillarSupport(serverLevel, cursor)) {
                return cursor.immutable();
            }
            cursor = cursor.below();
        }
        if (checked > 0 && !this.isStablePillarSupport(serverLevel, cursor)) {
            return cursor.immutable();
        }
        return null;
    }

    private boolean hasOtherEntityInBlock(ServerLevel serverLevel, BlockPos pos) {
        return !this.blockingEntitiesInBlock(serverLevel, pos).isEmpty();
    }

    private String entityBlockerText(ServerLevel serverLevel, BlockPos pos) {
        List<Entity> entities = this.blockingEntitiesInBlock(serverLevel, pos);
        if (entities.isEmpty()) {
            return "";
        }
        Entity entity = entities.get(0);
        return "entity in pillar block "
                + posText(pos)
                + " "
                + entity.getType().toShortString()
                + "#"
                + entity.getId();
    }

    private List<Entity> blockingEntitiesInBlock(ServerLevel serverLevel, BlockPos pos) {
        return PlayerNpcCollisionUtil.blockingEntitiesInBox(serverLevel, this.playerNpc, new AABB(pos).inflate(0.05D));
    }

    private void lookDownAt(BlockPos pos) {
        this.playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() - 0.5D,
                pos.getZ() + 0.5D,
                60.0F,
                60.0F
        );
    }

    private void lookAtFailureBlocker() {
        if (this.lastFailureBlockerPos == null) {
            return;
        }
        this.playerNpc.getLookControl().setLookAt(
                this.lastFailureBlockerPos.getX() + 0.5D,
                this.lastFailureBlockerPos.getY() + 0.5D,
                this.lastFailureBlockerPos.getZ() + 0.5D,
                60.0F,
                60.0F
        );
    }

    private static String blockText(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
