package com.pla.smart_npc.util;

import com.pla.smart_npc.network.PlayerNpcInspectatorModePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

public final class PlayerNpcCollisionUtil {
    private PlayerNpcCollisionUtil() {
    }

    public static boolean noBlockingCollision(ServerLevel serverLevel, Entity owner, AABB box) {
        return noBlockingBlockCollision(serverLevel, owner, box)
                && noBlockingEntityCollision(serverLevel, owner, box);
    }

    public static boolean noBlockingBlockCollision(ServerLevel serverLevel, Entity owner, AABB box) {
        for (VoxelShape shape : serverLevel.getBlockCollisions(owner, box)) {
            if (!shape.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static boolean noBlockingEntityCollision(ServerLevel serverLevel, Entity owner, AABB box) {
        return blockingEntitiesInBox(serverLevel, owner, box).isEmpty();
    }

    public static List<Entity> blockingEntitiesInBox(ServerLevel serverLevel, Entity owner, AABB box) {
        return serverLevel.getEntities(owner, box, entity -> isBlockingEntity(owner, entity));
    }

    public static boolean isBlockingEntity(Entity owner, Entity entity) {
        if (entity == null
                || !entity.isAlive()
                || entity instanceof ItemEntity
                || entity.isSpectator()
                || PlayerNpcInspectatorModePacket.isInspectatorActive(entity)) {
            return false;
        }
        if (owner == null) {
            return true;
        }
        if (entity == owner
                || entity.getVehicle() == owner
                || owner.getVehicle() == entity) {
            return false;
        }
        return !(entity.isPassenger() || owner.isPassenger())
                || entity.getRootVehicle() != owner.getRootVehicle();
    }
}
