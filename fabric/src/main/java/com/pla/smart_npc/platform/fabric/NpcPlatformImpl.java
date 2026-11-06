package com.pla.smart_npc.platform.fabric;
import com.pla.smart_npc.platform.NpcPlatform;
import com.mojang.authlib.GameProfile;
import com.pla.smart_npc.fabric.PersistentEntityData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemUseAnimation;

public final class NpcPlatformImpl {
    public static void initialize() {}
    public static CompoundTag persistentData(net.minecraft.world.level.block.entity.BlockEntity blockEntity) { return ((PersistentEntityData) blockEntity).smartNpc$persistentData(); }
    public static net.minecraft.world.entity.projectile.arrow.AbstractArrow customArrow(net.minecraft.world.item.BowItem bow, net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow, ItemStack ammunition, ItemStack weapon) { return arrow; }
    public static int experienceDrop(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.entity.BlockEntity blockEntity, net.minecraft.world.entity.LivingEntity entity, ItemStack tool) {
        if (state.getBlock() instanceof net.minecraft.world.level.block.DropExperienceBlock block) return ((com.pla.smart_npc.mixin.fabric.DropExperienceBlockAccessor) block).smartNpc$experience().sample(level.getRandom());
        if (state.is(net.minecraft.world.level.block.Blocks.SCULK)) return 1;
        if (state.is(net.minecraft.world.level.block.Blocks.SCULK_SENSOR) || state.is(net.minecraft.world.level.block.Blocks.SCULK_SHRIEKER)) return 5;
        return 0;
    }
    public static net.minecraft.world.level.block.SoundType soundType(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, Entity entity) { return state.getSoundType(); }
    public static boolean canPerformAction(ItemStack stack, String action) {
        return !stack.isEmpty() && switch (action) {
            case "SHIELD_BLOCK" -> stack.getUseAnimation() == ItemUseAnimation.BLOCK;
            case "FISHING_ROD_CAST" -> stack.getItem() instanceof FishingRodItem;
            default -> false;
        };
    }
    public static CompoundTag persistentData(Entity entity) { return ((PersistentEntityData) entity).smartNpc$persistentData(); }
    public static ServerPlayer tabPlayer(ServerLevel level, GameProfile profile) {
        ServerPlayer player = new com.pla.smart_npc.fabric.NpcTabPlayer(level, profile);
        ((com.pla.smart_npc.mixin.fabric.ServerPlayerGameModeAccessor) player.gameMode).smartNpc$setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        return player;
    }
    public static NpcPlatform.TeleportResult teleportCommand(Entity entity, ServerLevel targetLevel, double x, double y, double z) {
        return new NpcPlatform.TeleportResult(false, x, y, z);
    }
    public static boolean projectileImpact(net.minecraft.world.entity.projectile.Projectile entity, net.minecraft.world.phys.HitResult hit) { return false; }
    public static void hurtAndBreak(ItemStack stack, int amount, ServerLevel level, net.minecraft.world.entity.LivingEntity entity, java.util.function.Consumer<ItemStack> broken) { stack.hurtAndBreak(amount, level, null, broken); }
    public static net.minecraft.world.entity.EquipmentSlot equipmentSlot(ItemStack stack) { var equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE); return equippable == null ? null : equippable.slot(); }
    public static net.minecraft.world.InteractionHand bowHand(com.pla.smart_npc.entity.PlayerNpcEntity npc) { return npc.canFireProjectileWeapon(npc.getMainHandItem().getItem()) ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND; }
    public static net.minecraft.world.entity.ai.goal.Goal rangedBowGoal(com.pla.smart_npc.entity.PlayerNpcEntity npc, double speed, int interval, float radius) { return new com.pla.smart_npc.fabric.ai.FabricNpcRangedBowGoal(npc, speed, interval, radius); }
    private static final net.minecraft.server.level.TicketType NPC_TICKET = net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.TICKET_TYPE, net.minecraft.resources.Identifier.fromNamespaceAndPath("smart_npc", "player_npc_force_tick"), new net.minecraft.server.level.TicketType(0L, net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION));
    private static final java.util.Map<ServerLevel, java.util.Map<Long, java.util.Set<java.util.UUID>>> NPC_OWNERS = new java.util.WeakHashMap<>();
    public static void forceNpcChunk(ServerLevel level, java.util.UUID owner, int x, int z, boolean add, boolean ticking) {
        var chunks = NPC_OWNERS.computeIfAbsent(level, key -> new java.util.HashMap<>());
        var chunk = new net.minecraft.world.level.ChunkPos(x, z);
        if (add) {
            if (chunks.computeIfAbsent(chunk.pack(), key -> new java.util.HashSet<>()).add(owner)) level.getChunkSource().addTicketWithRadius(NPC_TICKET, chunk, 2);
        } else {
            var owners = chunks.get(chunk.pack());
            if (owners != null && owners.remove(owner) && owners.isEmpty()) {
                chunks.remove(chunk.pack());
                level.getChunkSource().removeTicketWithRadius(NPC_TICKET, chunk, 2);
            }
        }
    }
    public static boolean isOnlyNpcTicket(net.minecraft.world.level.TicketStorage storage, long chunk, net.minecraft.server.level.TicketType type) { return type == NPC_TICKET; }
    public static net.minecraft.world.item.component.ItemAttributeModifiers itemAttributes(ItemStack stack) { return stack.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY); }
}
