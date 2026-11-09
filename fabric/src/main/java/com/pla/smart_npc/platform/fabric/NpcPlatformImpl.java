package com.pla.smart_npc.platform.fabric;
import com.pla.smart_npc.platform.NpcPlatform;
import com.mojang.authlib.GameProfile;
import com.pla.smart_npc.fabric.PersistentEntityData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.UseAnim;

public final class NpcPlatformImpl {
    public static <T extends Entity> EntityType<T> createEntityType(String id, EntityType.EntityFactory<T> factory,
            MobCategory category, float width, float height, int trackingRangeChunks, int updateInterval) {
        // Custom entities have no vanilla data fixer; Fabric's builder keeps saving enabled without that lookup.
        return FabricEntityTypeBuilder.create(category, factory)
                .dimensions(EntityDimensions.scalable(width, height))
                .trackRangeChunks(trackingRangeChunks)
                .trackedUpdateRate(updateInterval)
                .build();
    }

    public static CompoundTag persistentData(net.minecraft.world.level.block.entity.BlockEntity blockEntity) { return ((PersistentEntityData) blockEntity).smartNpc$persistentData(); }
    public static boolean canDisableShield(ItemStack weapon, ItemStack shield, net.minecraft.world.entity.LivingEntity defender, net.minecraft.world.entity.LivingEntity attacker) { return weapon.getItem() instanceof net.minecraft.world.item.AxeItem; }
    public static net.minecraft.world.entity.projectile.AbstractArrow customArrow(net.minecraft.world.item.BowItem bow, net.minecraft.world.entity.projectile.AbstractArrow arrow) { return arrow; }
    public static int experienceDrop(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, int fortune, int silkTouch) {
        if (silkTouch > 0) return 0;
        if (state.getBlock() instanceof net.minecraft.world.level.block.DropExperienceBlock block) return ((com.pla.smart_npc.mixin.fabric.DropExperienceBlockAccessor) block).smartNpc$experience().sample(level.getRandom());
        if (state.is(net.minecraft.world.level.block.Blocks.SCULK)) return 1;
        if (state.is(net.minecraft.world.level.block.Blocks.SCULK_SENSOR) || state.is(net.minecraft.world.level.block.Blocks.SCULK_SHRIEKER)) return 5;
        return 0;
    }
    public static net.minecraft.world.level.block.SoundType soundType(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, Entity entity) { return state.getSoundType(); }
    public static boolean canPerformAction(ItemStack stack, String action) {
        return !stack.isEmpty() && switch (action) {
            case "SHIELD_BLOCK" -> stack.getUseAnimation() == UseAnim.BLOCK;
            case "FISHING_ROD_CAST" -> stack.getItem() instanceof FishingRodItem;
            default -> false;
        };
    }
    public static CompoundTag persistentData(Entity entity) { return ((PersistentEntityData) entity).smartNpc$persistentData(); }
    public static ServerPlayer tabPlayer(ServerLevel level, GameProfile profile) {
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile);
        ((com.pla.smart_npc.mixin.fabric.ServerPlayerGameModeAccessor) player.gameMode).smartNpc$setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        return player;
    }
    public static NpcPlatform.TeleportResult teleportCommand(Entity entity, double x, double y, double z) {
        return new NpcPlatform.TeleportResult(false, x, y, z);
    }
    public static boolean projectileImpact(net.minecraft.world.entity.projectile.Projectile entity, net.minecraft.world.phys.HitResult hit) { return false; }
}
