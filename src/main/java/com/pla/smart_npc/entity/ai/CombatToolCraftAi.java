package com.pla.smart_npc.entity.ai;

import net.minecraft.tags.ItemTags;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.goal.CraftBasicGearGoal;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcGearUtil;
import com.pla.smart_npc.util.PlayerNpcGearUtil.ToolKind;
import com.pla.smart_npc.util.PlayerNpcGearUtil.ToolTier;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Short, carried-material crafting owned by obstruction recovery's combat lock. */
public final class CombatToolCraftAi {
    private final PlayerNpcEntity npc;
    private final ToolAi tools;
    private final PlacingBlockAi placement;
    private BlockPos table;
    private Item result;
    private ToolKind recipeKind;
    private LivingEntity target;
    private boolean placeTable;
    private int actionTick;
    private int nextCheckTick;
    private String detail = "";
    private boolean waitingForAdmission;
    private int admissionDeferrals;

    public CombatToolCraftAi(PlayerNpcEntity npc) {
        this.npc = npc;
        this.tools = new ToolAi(npc);
        this.placement = new PlacingBlockAi(npc);
    }

    public boolean isRunning() {
        return this.result != null;
    }

    public boolean tick(ServerLevel level, BlockState obstruction, BlockPos obstructionPos) {
        if (!this.isRunning()) {
            if (this.tools.hasPreferredToolFor(obstruction)) {
                this.waitingForAdmission = false;
                return false;
            }
            if (this.npc.tickCount < this.nextCheckTick) return this.waitingForAdmission;
            ToolKind kind = kindFor(obstruction);
            if (kind == null) return false;
            if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.npc)) {
                this.waitingForAdmission = ++this.admissionDeferrals < 3;
                this.nextCheckTick = this.npc.tickCount + (this.waitingForAdmission
                        ? 20 + this.npc.getRandom().nextInt(6) : 600);
                return this.waitingForAdmission;
            }
            this.waitingForAdmission = false;
            this.admissionDeferrals = 0;
            this.nextCheckTick = this.npc.tickCount + 600 + this.npc.getRandom().nextInt(201);
            this.table = this.findTable(level);
            this.placeTable = this.table == null;
            if (this.placeTable) {
                // Do not overwrite ownership of an older temporary table elsewhere.
                if (CraftBasicGearGoal.hasValidTemporaryCraftingTable(this.npc, level)) return false;
                this.table = this.findPlacement(level, obstructionPos);
                if (this.table == null) return false;
            }
            for (ToolTier tier : new ToolTier[]{ToolTier.DIAMOND, ToolTier.IRON, ToolTier.STONE, ToolTier.WOOD}) {
                Item candidate = PlayerNpcGearUtil.itemFor(kind, tier);
                if (this.prepareInventory(level, candidate, this.placeTable) != null) {
                    this.result = candidate;
                    break;
                }
            }
            if (this.result == null) return false;
            this.target = this.npc.getTarget();
            this.recipeKind = kind;
            this.detail = "crafting " + this.result.getDefaultInstance().getHoverName().getString() + " for obstruction";
            this.actionTick = this.npc.tickCount + 12;
        }
        if (!this.npc.isAlive() || this.npc.isNoAi() || this.npc.isPassenger() || this.npc.isHealing()
                || this.target == null || !this.target.isAlive() || this.npc.getTarget() != this.target
                || kindFor(obstruction) != this.recipeKind || this.tools.hasPreferredToolFor(obstruction)
                || !this.inReach(this.table) || !level.hasChunkAt(this.table)) {
            this.stop();
            return false;
        }
        this.npc.getNavigation().stop();
        this.npc.getLookControl().setLookAt(this.table.getX() + 0.5D, this.table.getY() + 0.5D,
                this.table.getZ() + 0.5D, 40.0F, 40.0F);
        this.npc.setCurrentAiDetail(this.detail);
        if (this.npc.tickCount < this.actionTick) return true;
        if (this.placeTable) {
            if (!this.canPlaceTable(level, this.table, obstructionPos)
                    || this.prepareInventory(level, this.result, true) == null) {
                this.stop();
                return false;
            }
            SimpleContainer prepared = copy(this.npc.getInventory());
            if (!ensureTable(level, prepared)) {
                this.stop();
                return false;
            }
            commit(prepared, this.npc.getInventory());
            if (!this.tools.equipItem(Items.CRAFTING_TABLE)
                    || !this.placement.placeHeldBlock(level, this.table, Blocks.CRAFTING_TABLE.defaultBlockState())) {
                this.stop();
                return false;
            }
            this.npc.getPersistentData().putInt(CraftBasicGearGoal.TEMP_TABLE_X, this.table.getX());
            this.npc.getPersistentData().putInt(CraftBasicGearGoal.TEMP_TABLE_Y, this.table.getY());
            this.npc.getPersistentData().putInt(CraftBasicGearGoal.TEMP_TABLE_Z, this.table.getZ());
            this.tools.restoreMainHand();
            this.placeTable = false;
            this.actionTick = this.npc.tickCount + 12;
            return true;
        }
        if (level.getBlockState(this.table).is(Blocks.CRAFTING_TABLE)
                && ClearBlockAi.canBreakFromCurrentStand(level, this.npc, this.table)) {
            SimpleContainer prepared = this.prepareInventory(level, this.result, false);
            if (prepared != null) {
                commit(prepared, this.npc.getInventory());
                this.npc.triggerMainHandUseAnimation();
                this.npc.markCombatProgress();
            }
        }
        this.stop();
        return true;
    }

    public void stop() {
        this.tools.restoreMainHand();
        this.table = null;
        this.result = null;
        this.recipeKind = null;
        this.target = null;
        this.placeTable = false;
        this.detail = "";
        this.waitingForAdmission = false;
        // Retain the retry deadline across goal preemption; never restart a failed craft each tick.
    }

    private SimpleContainer prepareInventory(ServerLevel level, Item tool, boolean needsTable) {
        SimpleContainer prepared = copy(this.npc.getInventory());
        if (needsTable && (!ensureTable(level, prepared) || !takeTable(prepared))) return null;
        if (PlayerNpcCraftingUtil.countSticks(prepared) < 2
                && !craftWithWood(level, prepared, Items.STICK, false)) return null;
        return craftWithWood(level, prepared, tool, true) ? prepared : null;
    }

    private static boolean ensureTable(ServerLevel level, SimpleContainer inventory) {
        return PlayerNpcCraftingUtil.countItem(inventory, stack -> stack.is(Items.CRAFTING_TABLE)) > 0
                || craftWithWood(level, inventory, Items.CRAFTING_TABLE, false);
    }

    private static boolean takeTable(SimpleContainer inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).is(Items.CRAFTING_TABLE)) {
                inventory.removeItem(i, 1);
                return true;
            }
        }
        return false;
    }

    private static boolean craftWithWood(ServerLevel level, SimpleContainer inventory, Item item, boolean table) {
        // Cap conversions even if a data-pack recipe cannot be made from the carried materials.
        for (int attempt = 0; attempt < 4; attempt++) {
            if (PlayerNpcCraftingUtil.tryCraft(level, inventory, item, table)) return true;
            if (attempt == 3 || !PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(inventory, 0)) break;
        }
        return false;
    }

    private BlockPos findTable(ServerLevel level) {
        BlockPos feet = this.npc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 1, 1))) {
            if (level.hasChunkAt(pos) && this.inReach(pos) && level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)
                    && ClearBlockAi.canBreakFromCurrentStand(level, this.npc, pos)) return pos.immutable();
        }
        return null;
    }

    private BlockPos findPlacement(ServerLevel level, BlockPos obstruction) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            // Leave the forward approach clear; crafting may use a side or rear cell.
            double forward = direction.getStepX() * (obstruction.getX() + 0.5D - this.npc.getX())
                    + direction.getStepZ() * (obstruction.getZ() + 0.5D - this.npc.getZ());
            if (forward > 0.5D) continue;
            BlockPos pos = this.npc.blockPosition().relative(direction);
            if (this.canPlaceTable(level, pos, obstruction)) return pos.immutable();
        }
        return null;
    }

    private boolean canPlaceTable(ServerLevel level, BlockPos pos, BlockPos obstruction) {
        return !pos.equals(obstruction) && this.inReach(pos) && level.hasChunkAt(pos)
                && level.hasChunkAt(pos.below()) && level.isInWorldBounds(pos)
                && level.getWorldBorder().isWithinBounds(pos) && level.getBlockState(pos).isAir()
                && level.getBlockState(pos.below()).isCollisionShapeFullBlock(level, pos.below())
                && level.getFluidState(pos.below()).isEmpty()
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.npc, pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.npc, pos)
                && this.placement.canPlaceWithoutClipping(level, pos, Blocks.CRAFTING_TABLE.defaultBlockState());
    }

    private boolean inReach(BlockPos pos) {
        return pos != null && this.npc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 2.25D * 2.25D;
    }

    private static ToolKind kindFor(BlockState state) {
        Object preferred = ToolAi.preferredToolFor(state);
        return preferred == ItemTags.PICKAXES ? ToolKind.PICKAXE
                : preferred == AxeItem.class ? ToolKind.AXE : preferred == ShovelItem.class ? ToolKind.SHOVEL : null;
    }

    private static SimpleContainer copy(SimpleContainer inventory) {
        SimpleContainer copy = new SimpleContainer(inventory.getContainerSize());
        commit(inventory, copy);
        return copy;
    }

    private static void commit(SimpleContainer from, SimpleContainer to) {
        for (int i = 0; i < from.getContainerSize(); i++) to.setItem(i, from.getItem(i).copy());
        to.setChanged();
    }
}
