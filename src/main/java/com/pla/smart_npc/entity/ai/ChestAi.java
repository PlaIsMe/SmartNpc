package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.event.PlayerNpcChestProtectEvent;
import com.pla.smart_npc.util.ChatUtil;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class ChestAi {
    private ChestAi() {
    }

    public static BlockPos findHomeSupplyChest(PlayerNpcEntity playerNpc, ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        if (playerNpc == null || serverLevel == null || homeArea == null) {
            return null;
        }

        BlockPos ownedChestPos = playerNpc.getOwnedChestPos();
        if (ownedChestPos != null && !serverLevel.hasChunkAt(ownedChestPos)) {
            ownedChestPos = null;
        } else if (ownedChestPos != null && !serverLevel.getBlockState(ownedChestPos).is(Blocks.CHEST)) {
            clearMissingOwnedChest(playerNpc);
            ownedChestPos = null;
        }

        BlockPos preferred = findBlueprintChest(playerNpc, serverLevel, homeArea)
                .orElseGet(() -> findAnyHomeChest(serverLevel, homeArea).orElse(null));
        if (preferred != null) {
            if (ownedChestPos != null && !ownedChestPos.equals(preferred)) {
                migrateOwnedChest(playerNpc, serverLevel, ownedChestPos, preferred);
            }
            playerNpc.setOwnedChestPos(preferred);
            return preferred.immutable();
        }

        return ownedChestPos == null ? null : ownedChestPos.immutable();
    }

    /** Returns only this NPC's explicitly tracked chest, used by farm and camp bases. */
    public static BlockPos findOwnedSupplyChest(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return null;
        }
        BlockPos ownedChestPos = playerNpc.getOwnedChestPos();
        if (ownedChestPos == null) {
            return null;
        }
        if (!serverLevel.hasChunkAt(ownedChestPos)) {
            return null;
        }
        if (!serverLevel.getBlockState(ownedChestPos).is(Blocks.CHEST)) {
            clearMissingOwnedChest(playerNpc);
            return null;
        }
        return ownedChestPos.immutable();
    }

    private static void clearMissingOwnedChest(PlayerNpcEntity playerNpc) {
        // Clearing the retained position makes this a one-shot notification even though chest
        // discovery is polled by several goals. Unloaded chunks do not reach this path.
        playerNpc.setOwnedChestPos(null);
        ChatUtil.missingHomeChest(playerNpc);
    }

    public static BlockPos findAdjacentStand(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos chestPos) {
        return findAdjacentStand(playerNpc, serverLevel, chestPos, new NavigationPathBudget(4));
    }

    public static BlockPos findAdjacentStand(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos chestPos,
            NavigationPathBudget pathBudget
    ) {
        if (playerNpc == null || serverLevel == null || chestPos == null) {
            return null;
        }

        BlockPos current = playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(chestPos.relative(direction).immutable());
        }
        candidates.sort(Comparator.comparingDouble(current::distSqr));

        for (BlockPos candidate : candidates) {
            if (!canStandAt(serverLevel, candidate)) {
                continue;
            }
            if (candidate.equals(current)) {
                return candidate;
            }
            if (pathBudget == null || !pathBudget.tryConsume()) {
                break;
            }
            Path path = playerNpc.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    public static boolean isAtStand(PlayerNpcEntity playerNpc, BlockPos standPos) {
        return playerNpc != null
                && standPos != null
                && playerNpc.distanceToSqr(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D) <= 1.25D * 1.25D;
    }

    public static boolean moveToStand(PlayerNpcEntity playerNpc, BlockPos standPos, double speed) {
        if (playerNpc == null || standPos == null) {
            return false;
        }

        Path path = playerNpc.getNavigation().createPath(standPos, 0);
        return path != null && path.canReach() && playerNpc.getNavigation().moveTo(path, Math.min(speed, 1.0D));
    }

    public static boolean canAddToInventory(SimpleContainer inventory, ItemStack stack) {
        return stack.isEmpty() || addToInventoryPreview(inventory, stack).isEmpty();
    }

    public static int moveFromContainerToInventory(Container container, int slot, SimpleContainer inventory, int maxCount) {
        if (maxCount <= 0 || slot < 0 || slot >= container.getContainerSize()) {
            return 0;
        }

        ItemStack source = container.getItem(slot);
        if (source.isEmpty()) {
            return 0;
        }

        ItemStack moving = source.copy();
        moving.setCount(Math.min(maxCount, source.getCount()));
        int requested = moving.getCount();
        int moved = addToInventory(inventory, moving);
        if (moved <= 0) {
            return 0;
        }

        source.shrink(moved);
        if (source.isEmpty()) {
            container.setItem(slot, ItemStack.EMPTY);
        }
        container.setChanged();
        inventory.setChanged();
        return Math.min(moved, requested);
    }

    public static int addToInventory(SimpleContainer inventory, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        int originalCount = stack.getCount();
        mergeIntoInventory(inventory, stack, false);
        return originalCount - stack.getCount();
    }

    public static ItemStack addToContainerPreview(Container container, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack remainder = stack.copy();
        mergeIntoContainer(container, remainder, true);
        return remainder;
    }

    public static ItemStack addToContainer(Container container, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack remainder = stack.copy();
        mergeIntoContainer(container, remainder, false);
        container.setChanged();
        return remainder;
    }

    public static void openChest(ServerLevel serverLevel, BlockPos pos, PlayerNpcEntity opener) {
        if (!serverLevel.getBlockState(pos).is(Blocks.CHEST)) {
            return;
        }

        PlayerNpcChestProtectEvent.reportOffense(serverLevel, pos, opener, "opened");
        serverLevel.blockEvent(pos, Blocks.CHEST, 1, 1);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    public static void closeChest(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.getBlockState(pos).is(Blocks.CHEST)) {
            return;
        }

        serverLevel.blockEvent(pos, Blocks.CHEST, 1, 0);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private static Optional<BlockPos> findBlueprintChest(PlayerNpcEntity playerNpc, ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != homeArea.width()
                || layout.get().depth() != homeArea.depth()) {
            return Optional.empty();
        }

        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (!block.state().is(Blocks.CHEST)) {
                continue;
            }
            BlockPos pos = block.toWorld(homeArea.origin());
            if (serverLevel.getBlockState(pos).is(Blocks.CHEST)) {
                return Optional.of(pos.immutable());
            }
        }
        return Optional.empty();
    }

    private static Optional<BlockPos> findAnyHomeChest(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CHEST)) {
                return Optional.of(pos.immutable());
            }
        }
        return Optional.empty();
    }

    private static void migrateOwnedChest(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos oldPos, BlockPos newPos) {
        if (oldPos.equals(newPos)
                || !serverLevel.getBlockState(oldPos).is(Blocks.CHEST)
                || !serverLevel.getBlockState(newPos).is(Blocks.CHEST)
                || !(serverLevel.getBlockEntity(oldPos) instanceof Container oldChest)
                || !(serverLevel.getBlockEntity(newPos) instanceof Container newChest)) {
            return;
        }

        for (int slot = 0; slot < oldChest.getContainerSize(); slot++) {
            ItemStack stack = oldChest.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack remainder = addToContainer(newChest, stack.copy());
            if (!remainder.isEmpty()) {
                playerNpc.spawnAtLocation(remainder);
            }
            oldChest.setItem(slot, ItemStack.EMPTY);
        }
        ItemStack chestRemainder = addToContainer(newChest, new ItemStack(Items.CHEST));
        if (!chestRemainder.isEmpty()) {
            playerNpc.spawnAtLocation(chestRemainder);
        }
        oldChest.setChanged();
        newChest.setChanged();
        serverLevel.removeBlock(oldPos, false);
    }

    private static ItemStack addToInventoryPreview(SimpleContainer inventory, ItemStack stack) {
        ItemStack remainder = stack.copy();
        mergeIntoInventory(inventory, remainder, true);
        return remainder;
    }

    private static void mergeIntoInventory(SimpleContainer inventory, ItemStack remainder, boolean preview) {
        mergeIntoContainer(inventory, remainder, preview);
        if (!preview) {
            inventory.setChanged();
        }
    }

    private static void mergeIntoContainer(Container container, ItemStack remainder, boolean preview) {
        if (remainder.isEmpty()) {
            return;
        }

        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, remainder)) {
                continue;
            }

            int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize()) - existing.getCount();
            if (room <= 0) {
                continue;
            }

            int moving = Math.min(room, remainder.getCount());
            if (!preview) {
                existing.grow(moving);
                container.setItem(slot, existing);
            }
            remainder.shrink(moving);
        }

        for (int slot = 0; slot < container.getContainerSize() && !remainder.isEmpty(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty()) {
                continue;
            }

            int moving = Math.min(Math.min(remainder.getMaxStackSize(), container.getMaxStackSize()), remainder.getCount());
            ItemStack moved = remainder.copy();
            moved.setCount(moving);
            if (!preview) {
                container.setItem(slot, moved);
            }
            remainder.shrink(moving);
        }
    }

    /** Mutable total budget shared by every candidate in one chest-placement search. */
    public static final class NavigationPathBudget {
        private int remaining;

        public NavigationPathBudget(int maximumPaths) {
            this.remaining = Math.max(0, maximumPaths);
        }

        public boolean tryConsume() {
            if (this.remaining <= 0) {
                return false;
            }
            this.remaining--;
            return true;
        }

        public boolean exhausted() {
            return this.remaining <= 0;
        }
    }
}
