package com.pla.smart_npc.platform;

import com.mojang.authlib.GameProfile;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

public final class NpcPlatform {
    @ExpectPlatform
    public static void initialize() { throw new AssertionError(); }
    private NpcPlatform() {}
    public record TeleportResult(boolean isCanceled, double getTargetX, double getTargetY, double getTargetZ) {}
    @ExpectPlatform
    public static TeleportResult teleportCommand(Entity entity, ServerLevel targetLevel, double x, double y, double z) { throw new AssertionError(); }
    @ExpectPlatform
    public static boolean projectileImpact(net.minecraft.world.entity.projectile.Projectile entity, net.minecraft.world.phys.HitResult hit) { throw new AssertionError(); }


    @ExpectPlatform
    public static boolean canPerformAction(ItemStack stack, String action) { throw new AssertionError(); }

    @ExpectPlatform
    public static CompoundTag persistentData(Entity entity) { throw new AssertionError(); }

    @ExpectPlatform
    public static ServerPlayer tabPlayer(ServerLevel level, GameProfile profile) { throw new AssertionError(); }

    @ExpectPlatform
    public static CompoundTag persistentData(net.minecraft.world.level.block.entity.BlockEntity blockEntity) { throw new AssertionError(); }


    @ExpectPlatform
    public static net.minecraft.world.entity.projectile.arrow.AbstractArrow customArrow(net.minecraft.world.item.BowItem bow, net.minecraft.world.entity.projectile.arrow.AbstractArrow arrow, ItemStack ammunition, ItemStack weapon) { throw new AssertionError(); }

    @ExpectPlatform
    public static int experienceDrop(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.entity.BlockEntity blockEntity, net.minecraft.world.entity.LivingEntity entity, ItemStack tool) { throw new AssertionError(); }

    @ExpectPlatform
    public static net.minecraft.world.level.block.SoundType soundType(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, Entity entity) { throw new AssertionError(); }
    @ExpectPlatform
    public static void hurtAndBreak(ItemStack stack, int amount, ServerLevel level, net.minecraft.world.entity.LivingEntity entity, java.util.function.Consumer<ItemStack> broken) { throw new AssertionError(); }
    @ExpectPlatform
    public static net.minecraft.world.entity.EquipmentSlot equipmentSlot(ItemStack stack) { throw new AssertionError(); }
    @ExpectPlatform
    public static net.minecraft.world.InteractionHand bowHand(com.pla.smart_npc.entity.PlayerNpcEntity npc) { throw new AssertionError(); }
    @ExpectPlatform
    public static net.minecraft.world.entity.ai.goal.Goal rangedBowGoal(com.pla.smart_npc.entity.PlayerNpcEntity npc, double speed, int interval, float radius) { throw new AssertionError(); }
    @ExpectPlatform
    public static void forceNpcChunk(ServerLevel level, java.util.UUID owner, int x, int z, boolean add, boolean ticking) { throw new AssertionError(); }
    @ExpectPlatform
    public static boolean isOnlyNpcTicket(net.minecraft.world.level.TicketStorage storage, long chunk, net.minecraft.server.level.TicketType type) { throw new AssertionError(); }
    @ExpectPlatform
    public static net.minecraft.world.item.component.ItemAttributeModifiers itemAttributes(ItemStack stack) { throw new AssertionError(); }
}
