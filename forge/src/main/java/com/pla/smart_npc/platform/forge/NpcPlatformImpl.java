package com.pla.smart_npc.platform.forge;
import com.pla.smart_npc.platform.NpcPlatform;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.util.FakePlayerFactory;

public final class NpcPlatformImpl {
    public static <T extends Entity> EntityType<T> createEntityType(String id, EntityType.EntityFactory<T> factory,
            MobCategory category, float width, float height, int trackingRangeChunks, int updateInterval) {
        return EntityType.Builder.of(factory, category)
                .sized(width, height)
                .clientTrackingRange(trackingRangeChunks)
                .updateInterval(updateInterval)
                .build(id);
    }

    public static CompoundTag persistentData(net.minecraft.world.level.block.entity.BlockEntity blockEntity) { return blockEntity.getPersistentData(); }
    public static boolean canDisableShield(ItemStack weapon, ItemStack shield, net.minecraft.world.entity.LivingEntity defender, net.minecraft.world.entity.LivingEntity attacker) { return weapon.canDisableShield(shield, defender, attacker); }
    public static net.minecraft.world.entity.projectile.AbstractArrow customArrow(net.minecraft.world.item.BowItem bow, net.minecraft.world.entity.projectile.AbstractArrow arrow) { return bow.customArrow(arrow); }
    public static int experienceDrop(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, int fortune, int silkTouch) { return state.getExpDrop(level, level.getRandom(), pos, fortune, silkTouch); }
    public static net.minecraft.world.level.block.SoundType soundType(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, Entity entity) { return state.getSoundType(level, pos, entity); }
    public static boolean canPerformAction(ItemStack stack, String action) { return stack.canPerformAction(ToolAction.get(action.toLowerCase(java.util.Locale.ROOT))); }
    public static CompoundTag persistentData(Entity entity) { return entity.getPersistentData(); }
    public static ServerPlayer tabPlayer(ServerLevel level, GameProfile profile) { return FakePlayerFactory.get(level, profile); }
    public static NpcPlatform.TeleportResult teleportCommand(Entity entity, double x, double y, double z) {
        var event = net.minecraftforge.event.ForgeEventFactory.onEntityTeleportCommand(entity, x, y, z);
        return new NpcPlatform.TeleportResult(event.isCanceled(), event.getTargetX(), event.getTargetY(), event.getTargetZ());
    }
    public static boolean projectileImpact(net.minecraft.world.entity.projectile.Projectile entity, net.minecraft.world.phys.HitResult hit) { return net.minecraftforge.event.ForgeEventFactory.onProjectileImpact(entity, hit); }
}
