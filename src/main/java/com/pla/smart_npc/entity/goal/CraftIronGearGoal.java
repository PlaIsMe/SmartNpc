package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.util.SmartNpcItemUtil;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcGearUtil;
import com.pla.smart_npc.util.PlayerNpcGearUtil.ToolKind;
import com.pla.smart_npc.util.PlayerNpcGearUtil.ToolTier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class CraftIronGearGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 10;
    private static final int CRAFTING_TABLE_SCAN_RADIUS = 5;
    private static final int CAN_USE_CHECK_INTERVAL_TICKS = 40;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(CAN_USE_CHECK_INTERVAL_TICKS);

    public CraftIronGearGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getIronGearCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)
                || !this.hasNearbyCraftingTable(serverLevel)) {
            return false;
        }

        return this.canCraftAnyGear(serverLevel);
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.crafting_gear_upgrade");
        boolean crafted = this.tryCraftGear(serverLevel);
        if (crafted) {
            this.playerNpc.triggerMainHandUseAnimation();
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.35F, 1.4F);
        }
        this.playerNpc.setIronGearCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 12));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean canCraftAnyGear(ServerLevel serverLevel) {
        return this.canCraftTool(serverLevel, ToolKind.PICKAXE, ToolTier.DIAMOND, Items.DIAMOND)
                || this.canCraftTool(serverLevel, ToolKind.SWORD, ToolTier.DIAMOND, Items.DIAMOND)
                || this.canCraftTool(serverLevel, ToolKind.AXE, ToolTier.DIAMOND, Items.DIAMOND)
                || this.canCraftTool(serverLevel, ToolKind.SHOVEL, ToolTier.DIAMOND, Items.DIAMOND)
                || this.canImproveArmor(serverLevel, EquipmentSlot.HEAD, Items.DIAMOND_HELMET, Items.DIAMOND, 5)
                || this.canImproveArmor(serverLevel, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, Items.DIAMOND, 8)
                || this.canImproveArmor(serverLevel, EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, Items.DIAMOND, 7)
                || this.canImproveArmor(serverLevel, EquipmentSlot.FEET, Items.DIAMOND_BOOTS, Items.DIAMOND, 4)
                || this.canCraftTool(serverLevel, ToolKind.PICKAXE, ToolTier.IRON, Items.IRON_INGOT)
                || this.canCraftTool(serverLevel, ToolKind.AXE, ToolTier.IRON, Items.IRON_INGOT)
                || this.canCraftTool(serverLevel, ToolKind.SWORD, ToolTier.IRON, Items.IRON_INGOT)
                || this.canCraftTool(serverLevel, ToolKind.SHOVEL, ToolTier.IRON, Items.IRON_INGOT)
                || this.canImproveArmor(serverLevel, EquipmentSlot.HEAD, Items.IRON_HELMET, Items.IRON_INGOT, 5)
                || this.canImproveArmor(serverLevel, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, Items.IRON_INGOT, 8)
                || this.canImproveArmor(serverLevel, EquipmentSlot.LEGS, Items.IRON_LEGGINGS, Items.IRON_INGOT, 7)
                || this.canImproveArmor(serverLevel, EquipmentSlot.FEET, Items.IRON_BOOTS, Items.IRON_INGOT, 4);
    }

    private boolean tryCraftGear(ServerLevel serverLevel) {
        return this.tryCraftTool(serverLevel, ToolKind.PICKAXE, ToolTier.DIAMOND, Items.DIAMOND)
                || this.tryCraftTool(serverLevel, ToolKind.SWORD, ToolTier.DIAMOND, Items.DIAMOND)
                || this.tryCraftTool(serverLevel, ToolKind.AXE, ToolTier.DIAMOND, Items.DIAMOND)
                || this.tryCraftTool(serverLevel, ToolKind.SHOVEL, ToolTier.DIAMOND, Items.DIAMOND)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, Items.DIAMOND, 8)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, Items.DIAMOND, 7)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.HEAD, Items.DIAMOND_HELMET, Items.DIAMOND, 5)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.FEET, Items.DIAMOND_BOOTS, Items.DIAMOND, 4)
                || this.tryCraftTool(serverLevel, ToolKind.PICKAXE, ToolTier.IRON, Items.IRON_INGOT)
                || this.tryCraftTool(serverLevel, ToolKind.AXE, ToolTier.IRON, Items.IRON_INGOT)
                || this.tryCraftTool(serverLevel, ToolKind.SWORD, ToolTier.IRON, Items.IRON_INGOT)
                || this.tryCraftTool(serverLevel, ToolKind.SHOVEL, ToolTier.IRON, Items.IRON_INGOT)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, Items.IRON_INGOT, 8)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.LEGS, Items.IRON_LEGGINGS, Items.IRON_INGOT, 7)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.HEAD, Items.IRON_HELMET, Items.IRON_INGOT, 5)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.FEET, Items.IRON_BOOTS, Items.IRON_INGOT, 4);
    }

    private boolean canCraftTool(ServerLevel serverLevel, ToolKind kind, ToolTier tier, ItemLike material) {
        Item result = PlayerNpcGearUtil.itemFor(kind, tier);
        return this.bestToolTier(kind).isBelow(tier)
                && this.countMaterial(material) >= kind.materialCost()
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, kind.stickCost(), this.playerNpc.getRawLogReserveTarget())
                && (PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory()) < kind.stickCost()
                || PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), result, true));
    }

    private boolean tryCraftTool(ServerLevel serverLevel, ToolKind kind, ToolTier tier, ItemLike material) {
        Item result = PlayerNpcGearUtil.itemFor(kind, tier);
        if (!this.canCraftTool(serverLevel, kind, tier, material)
                || !this.ensureSticks(serverLevel, kind.stickCost())) {
            return false;
        }
        boolean crafted = PlayerNpcCraftingUtil.craftItem(serverLevel, this.playerNpc.getInventory(), result, true)
                .map(stack -> InventoryUtils.addItem(this.playerNpc, stack))
                .orElse(false);
        if (crafted) {
            this.playerNpc.equipBetterGearFromInventory();
        }
        return crafted;
    }

    private boolean canImproveArmor(ServerLevel serverLevel, EquipmentSlot slot, ItemLike result, ItemLike material, int materialNeeded) {
        return this.countMaterial(material) >= materialNeeded
                && this.isArmorUpgrade(slot, result.asItem())
                && PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), result, true);
    }

    private boolean tryCraftArmor(ServerLevel serverLevel, EquipmentSlot slot, ItemLike result, ItemLike material, int materialNeeded) {
        if (!this.canImproveArmor(serverLevel, slot, result, material, materialNeeded)) {
            return false;
        }

        ItemStack crafted = PlayerNpcCraftingUtil.craftItem(serverLevel, this.playerNpc.getInventory(), result, true).orElse(ItemStack.EMPTY);
        if (crafted.isEmpty()) {
            return false;
        }

        ItemStack previous = this.playerNpc.getItemBySlot(slot);
        if (!previous.isEmpty() && !InventoryUtils.addItem(this.playerNpc, previous.copy())) {
            this.playerNpc.spawnAtLocation(previous.copy());
        }
        ItemStack equipped = crafted.copy();
        equipped.setCount(1);
        this.playerNpc.setItemSlot(slot, equipped);
        return true;
    }

    private boolean ensureSticks(ServerLevel serverLevel, int sticksNeeded) {
        while (PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory()) < sticksNeeded) {
            if (PlayerNpcCraftingUtil.countPlanks(this.playerNpc.getInventory()) < 2
                    && PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) > this.playerNpc.getRawLogReserveTarget()
                    && PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
                continue;
            }
            if (!PlayerNpcCraftingUtil.tryCraftSticks(serverLevel, this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
                return false;
            }
        }
        return true;
    }

    private boolean hasItem(ItemLike itemLike) {
        return this.playerNpc.getMainHandItem().is(itemLike.asItem())
                || this.playerNpc.getOffhandItem().is(itemLike.asItem())
                || InventoryUtils.hasItem(this.playerNpc, itemLike);
    }

    private boolean isArmorUpgrade(EquipmentSlot slot, Item item) {
        ItemStack newStack = item.getDefaultInstance();
        if (!SmartNpcItemUtil.isArmor(newStack)) {
            return false;
        }

        ItemStack currentStack = this.playerNpc.getItemBySlot(slot);
        if (currentStack.isEmpty() || !SmartNpcItemUtil.isArmor(currentStack)) {
            return true;
        }

        return SmartNpcItemUtil.armorScore(newStack) > SmartNpcItemUtil.armorScore(currentStack);
    }

    private int countMaterial(ItemLike material) {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(material.asItem()));
    }

    private ToolTier bestToolTier(ToolKind kind) {
        ToolTier best = PlayerNpcGearUtil.bestToolTier(
                this.playerNpc.getMainHandItem(),
                this.playerNpc.getOffhandItem(),
                this.playerNpc.getInventory(),
                kind
        );
        return best;
    }

    private boolean hasNearbyCraftingTable(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-CRAFTING_TABLE_SCAN_RADIUS, -2, -CRAFTING_TABLE_SCAN_RADIUS),
                origin.offset(CRAFTING_TABLE_SCAN_RADIUS, 2, CRAFTING_TABLE_SCAN_RADIUS))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }
}
