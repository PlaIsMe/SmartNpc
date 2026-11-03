package com.pla.smart_npc.platform;

import com.mojang.authlib.GameProfile;
import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

public final class NpcPlatform {
    private NpcPlatform() {}
    public record TeleportResult(boolean isCanceled, double getTargetX, double getTargetY, double getTargetZ) {}
    @ExpectPlatform
    public static TeleportResult teleportCommand(Entity entity, double x, double y, double z) { throw new AssertionError(); }
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
    public static boolean canDisableShield(ItemStack weapon, ItemStack shield, net.minecraft.world.entity.LivingEntity defender, net.minecraft.world.entity.LivingEntity attacker) { throw new AssertionError(); }

    @ExpectPlatform
    public static net.minecraft.world.entity.projectile.AbstractArrow customArrow(net.minecraft.world.item.BowItem bow, net.minecraft.world.entity.projectile.AbstractArrow arrow) { throw new AssertionError(); }

    @ExpectPlatform
    public static int experienceDrop(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, int fortune, int silkTouch) { throw new AssertionError(); }

    @ExpectPlatform
    public static net.minecraft.world.level.block.SoundType soundType(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, Entity entity) { throw new AssertionError(); }
}
