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
import net.minecraft.world.item.UseAnim;

public final class NpcPlatformImpl {
    public static CompoundTag persistentData(net.minecraft.world.level.block.entity.BlockEntity blockEntity) { return ((PersistentEntityData) blockEntity).smartNpc$persistentData(); }
    public static boolean canDisableShield(ItemStack weapon, ItemStack shield, net.minecraft.world.entity.LivingEntity defender, net.minecraft.world.entity.LivingEntity attacker) { return weapon.getItem() instanceof net.minecraft.world.item.AxeItem; }
    public static net.minecraft.world.entity.projectile.AbstractArrow customArrow(net.minecraft.world.item.BowItem bow, net.minecraft.world.entity.projectile.AbstractArrow arrow, ItemStack ammunition, ItemStack weapon) { return arrow; }
    public static int experienceDrop(net.minecraft.world.level.block.state.BlockState state, ServerLevel level, net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.entity.BlockEntity blockEntity, net.minecraft.world.entity.LivingEntity entity, ItemStack tool) {
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
        ServerPlayer player = new com.pla.smart_npc.fabric.NpcTabPlayer(level, profile);
        ((com.pla.smart_npc.mixin.fabric.ServerPlayerGameModeAccessor) player.gameMode).smartNpc$setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        return player;
    }
    public static NpcPlatform.TeleportResult teleportCommand(Entity entity, double x, double y, double z) {
        return new NpcPlatform.TeleportResult(false, x, y, z);
    }
    public static boolean projectileImpact(net.minecraft.world.entity.projectile.Projectile entity, net.minecraft.world.phys.HitResult hit) { return false; }
    public static void hurtAndBreak(ItemStack stack, int amount, ServerLevel level, net.minecraft.world.entity.LivingEntity entity, java.util.function.Consumer<net.minecraft.world.item.Item> broken) { stack.hurtAndBreak(amount, level, null, broken); }
    public static net.minecraft.world.item.component.ItemAttributeModifiers itemAttributes(ItemStack stack) { return stack.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS, net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY); }
    public static net.minecraft.world.entity.EquipmentSlot equipmentSlot(ItemStack stack) { var equipable = net.minecraft.world.item.Equipable.get(stack); return equipable == null ? null : equipable.getEquipmentSlot(); }
    public static net.minecraft.world.InteractionHand bowHand(com.pla.smart_npc.entity.PlayerNpcEntity npc) { return npc.canFireProjectileWeapon(npc.getMainHandItem().getItem()) ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND; }
    public static net.minecraft.world.entity.ai.goal.Goal rangedBowGoal(com.pla.smart_npc.entity.PlayerNpcEntity npc, double speed, int interval, float radius) { return new com.pla.smart_npc.fabric.ai.FabricNpcRangedBowGoal(npc, speed, interval, radius); }
    public static <T extends net.minecraft.world.level.saveddata.SavedData> net.minecraft.world.level.saveddata.SavedData.Factory<T> savedDataFactory(java.util.function.Supplier<T> constructor, java.util.function.BiFunction<CompoundTag, net.minecraft.core.HolderLookup.Provider, T> load) { return new net.minecraft.world.level.saveddata.SavedData.Factory<>(constructor, load, net.minecraft.util.datafix.DataFixTypes.LEVEL); }
}
