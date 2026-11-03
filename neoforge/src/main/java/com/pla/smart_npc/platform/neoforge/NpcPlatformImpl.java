package com.pla.smart_npc.platform.neoforge;
import com.pla.smart_npc.platform.NpcPlatform;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
public final class NpcPlatformImpl {
    public static void initialize() {}
    public static CompoundTag persistentData(Entity entity) { return entity.getPersistentData(); }
    public static CompoundTag persistentData(BlockEntity entity) { return entity.getPersistentData(); }
    public static boolean canPerformAction(ItemStack stack, String action) { return stack.canPerformAction(net.neoforged.neoforge.common.ItemAbility.get(action.toLowerCase(java.util.Locale.ROOT))); }
    public static net.minecraft.world.entity.projectile.arrow.AbstractArrow customArrow(net.minecraft.world.item.BowItem bow, net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow, ItemStack ammunition, ItemStack weapon) { return bow.customArrow(arrow, ammunition, weapon); }
    public static int experienceDrop(BlockState state, ServerLevel level, BlockPos pos, BlockEntity blockEntity, LivingEntity entity, ItemStack tool) { return state.getExpDrop(level, pos, blockEntity, entity, tool); }
    public static net.minecraft.world.level.block.SoundType soundType(BlockState state, ServerLevel level, BlockPos pos, Entity entity) { return state.getSoundType(level, pos, entity); }
    public static ServerPlayer tabPlayer(ServerLevel level, GameProfile profile) { return new net.neoforged.neoforge.common.util.FakePlayer(level, profile); }
    public static NpcPlatform.TeleportResult teleportCommand(Entity entity, double x, double y, double z) {
        var event = new net.neoforged.neoforge.event.entity.EntityTeleportEvent.TeleportCommand(entity, x, y, z);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        return new NpcPlatform.TeleportResult(event.isCanceled(), event.getTargetX(), event.getTargetY(), event.getTargetZ());
    }
    public static boolean projectileImpact(net.minecraft.world.entity.projectile.Projectile entity, net.minecraft.world.phys.HitResult hit) { return net.neoforged.neoforge.event.EventHooks.onProjectileImpact(entity, hit); }
    public static void hurtAndBreak(ItemStack stack, int amount, ServerLevel level, net.minecraft.world.entity.LivingEntity entity, java.util.function.Consumer<net.minecraft.world.item.Item> broken) { stack.hurtAndBreak(amount, level, entity, broken); }
    public static net.minecraft.world.entity.EquipmentSlot equipmentSlot(ItemStack stack) { return stack.getEquipmentSlot(); }
    public static net.minecraft.world.InteractionHand bowHand(com.pla.smart_npc.entity.PlayerNpcEntity npc) { return net.minecraft.world.entity.projectile.ProjectileUtil.getWeaponHoldingHand(npc, npc::canFireProjectileWeapon); }
    public static net.minecraft.world.entity.ai.goal.Goal rangedBowGoal(com.pla.smart_npc.entity.PlayerNpcEntity npc, double speed, int interval, float radius) { return new net.minecraft.world.entity.ai.goal.RangedBowAttackGoal<>(npc, speed, interval, radius); }
    public static final net.neoforged.neoforge.common.world.chunk.TicketController NPC_TICKET = new net.neoforged.neoforge.common.world.chunk.TicketController(net.minecraft.resources.Identifier.fromNamespaceAndPath("smart_npc", "player_npc_force_tick"));
    public static void forceNpcChunk(ServerLevel level, java.util.UUID owner, int x, int z, boolean add, boolean ticking) { NPC_TICKET.forceChunk(level, owner, x, z, add, ticking); }
    public static boolean isOnlyNpcTicket(net.minecraft.world.level.TicketStorage storage, long chunk, net.minecraft.server.level.TicketType type) {
        if (type != net.neoforged.neoforge.common.NeoForgeMod.ENTITY_TICKET.value()) return false;
        var sources = ((com.pla.smart_npc.mixin.neoforge.TicketTrackerAccessor) storage.getEntityForcedChunks()).smartNpc$getSourcesLoading().get(chunk);
        if (sources == null || sources.isEmpty()) return false;
        for (Object source : sources) {
            if (!((com.pla.smart_npc.mixin.neoforge.TicketOwnerAccessor) source).smartNpc$getControllerId().equals(NPC_TICKET.id())) return false;
        }
        return true;
    }
    public static net.minecraft.world.item.component.ItemAttributeModifiers itemAttributes(ItemStack stack) { return stack.getAttributeModifiers(); }
}
