package com.pla.smart_npc.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.ResourceAi;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** Short, session-owned requests; existing work goals keep all their normal safety checks. */
public final class NpcGoalBridge {
    public record Order(AiAction.Type type, UUID player, String item, int targetCount, int expiresAt) {
        public boolean work() { return type == AiAction.Type.GATHER_ITEM || type == AiAction.Type.MINE_RESOURCE; }
    }
    private NpcGoalBridge() {}
    public static Order order(PlayerNpcEntity npc) {
        AiChatService service = AiChatService.get(npc.getServer());
        return service == null ? null : service.order(npc.getUUID());
    }
    public static boolean hasWork(PlayerNpcEntity npc) {
        Order order = order(npc);
        return order != null && order.work();
    }
    public static boolean pausesWork(PlayerNpcEntity npc) {
        Order order = order(npc);
        return order != null && order.type() == AiAction.Type.STOP_CURRENT_GOAL;
    }
    public static boolean blocksFollowing(PlayerNpcEntity npc) { return order(npc) != null; }
    public static boolean needsLogs(PlayerNpcEntity npc) {
        Order order = order(npc);
        return order != null && order.type() == AiAction.Type.GATHER_ITEM && count(npc, order.item()) < order.targetCount();
    }
    public static boolean acceptsLog(PlayerNpcEntity npc, BlockState state) {
        Order order = order(npc);
        return order == null || order.type() != AiAction.Type.GATHER_ITEM
                || BuiltInRegistries.ITEM.getKey(state.getBlock().asItem()).toString().equals(order.item());
    }
    public static boolean acceptsOre(PlayerNpcEntity npc, BlockState state) {
        Order order = order(npc);
        if (order == null || order.type() != AiAction.Type.MINE_RESOURCE) return true;
        String ore = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        String resource = order.item().substring("minecraft:".length()).replace("raw_", "");
        return ore.equals(resource + "_ore") || ore.equals("deepslate_" + resource + "_ore");
    }
    public static int count(PlayerNpcEntity npc, String item) {
        int result = 0;
        for (int slot = 0; slot < npc.getInventory().getContainerSize(); slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)) result += stack.getCount();
        }
        return result;
    }
    /** Namespaced selectors are exact; bare words match registry paths, including modded items. */
    public static List<String> giftCandidates(PlayerNpcEntity npc, AiAction action, AiConfig config) {
        List<String> candidates = new ArrayList<>();
        String query = action.item();
        if (query == null || !query.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+") || action.count() < 0 || action.count() > config.maxGiftCount) return candidates;
        for (int slot = 0; slot < npc.getInventory().getContainerSize(); slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            String item = id.toString();
            if (query.contains(":") ? !item.equals(query) : !id.getPath().contains(query)) continue;
            if (!(config.giftItems.contains("*") || config.giftItems.contains(item)) || candidates.contains(item)) continue;
            if (count(npc, item) >= Math.max(1, action.count())) candidates.add(item);
        }
        return candidates;
    }
    /** Resolve once on the server thread, then use the existing atomic inventory transfer. */
    public static boolean give(PlayerNpcEntity npc, ServerPlayer player, AiAction action, AiConfig config) {
        List<String> candidates = giftCandidates(npc, action, config);
        if (candidates.isEmpty()) return false;
        String item = candidates.get(npc.getRandom().nextInt(candidates.size()));
        int amount = action.count() == 0 ? 1 + npc.getRandom().nextInt(Math.min(config.maxGiftCount, count(npc, item))) : action.count();
        return give(npc, player, new AiAction(AiAction.Type.GIVE_ITEM, "PLAYER", item, amount));
    }
    /** Preflight total capacity for each exact component-bearing stack before committing anything. */
    public static boolean give(PlayerNpcEntity npc, ServerPlayer player, AiAction action) {
        ResourceLocation id = ResourceLocation.tryParse(action.item());
        if (id == null || count(npc, action.item()) < action.count()) return false;
        ItemStack[] destination = new ItemStack[36];
        for (int i = 0; i < destination.length; i++) destination[i] = player.getInventory().getItem(i).copy();
        int remaining = action.count();
        int[] remove = new int[npc.getInventory().getContainerSize()];
        for (int slot = 0; slot < remove.length && remaining > 0; slot++) {
            ItemStack source = npc.getInventory().getItem(slot);
            if (!BuiltInRegistries.ITEM.getKey(source.getItem()).equals(id)) continue;
            int amount = Math.min(remaining, source.getCount());
            ItemStack transfer = source.copyWithCount(amount);
            for (int pass = 0; pass < 2 && !transfer.isEmpty(); pass++) {
                for (int i = 0; i < destination.length && !transfer.isEmpty(); i++) {
                    ItemStack target = destination[i];
                    if (pass == 0 && !target.isEmpty() && ItemStack.isSameItemSameTags(target, transfer)) {
                        int inserted = Math.min(transfer.getCount(), Math.min(64, target.getMaxStackSize()) - target.getCount());
                        if (inserted > 0) { target.grow(inserted); transfer.shrink(inserted); }
                    } else if (pass == 1 && target.isEmpty()) {
                        int inserted = Math.min(transfer.getCount(), Math.min(64, transfer.getMaxStackSize()));
                        destination[i] = transfer.copyWithCount(inserted);
                        transfer.shrink(inserted);
                    }
                }
            }
            if (!transfer.isEmpty()) return false;
            remove[slot] = amount;
            remaining -= amount;
        }
        if (remaining != 0) return false;
        // Both containers belong to this server thread, and no callbacks run during preflight.
        for (int slot = 0; slot < remove.length; slot++) if (remove[slot] > 0) npc.getInventory().removeItem(slot, remove[slot]);
        for (int slot = 0; slot < destination.length; slot++) player.getInventory().setItem(slot, destination[slot]);
        npc.getInventory().setChanged();
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
        ResourceAi.invalidate(npc);
        return true;
    }
}
