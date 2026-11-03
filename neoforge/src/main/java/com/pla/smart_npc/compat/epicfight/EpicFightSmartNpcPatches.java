package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.init.SmartNpcModEntities;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import yesman.epicfight.api.event.types.registry.EntityPatchRegistryEvent;
import yesman.epicfight.api.event.EpicFightEventHooks;
import yesman.epicfight.registry.entries.EpicFightAttributes;

public final class EpicFightSmartNpcPatches {
    private static boolean patchHookRegistered;

    private EpicFightSmartNpcPatches() {
    }

    public static void registerPatchHook() {
        if (!patchHookRegistered) {
            patchHookRegistered = true;
            EpicFightEventHooks.Registry.ENTITY_PATCH.registerEvent(
                    EpicFightSmartNpcPatches::setPatch, SmartNpc.MODID);
        }
    }

    public static void setPatch(EntityPatchRegistryEvent event) {
        event.registerEntityPatch(SmartNpcModEntities.PLAYER_NPC.get(), AdvancedPlayerNpcPatch::new);
    }


    @SubscribeEvent
    public static void addEpicFightAttributes(EntityAttributeModificationEvent event) {
        var type = SmartNpcModEntities.PLAYER_NPC.get();
        // Epic Fight 21 scales mob attack animations using this attribute too.
        // Use the player base; equipped weapons apply their own speed modifiers.
        event.add(type, Attributes.ATTACK_SPEED, 4.0D);
        event.add(type, EpicFightAttributes.WEIGHT);
        event.add(type, EpicFightAttributes.ARMOR_NEGATION);
        event.add(type, EpicFightAttributes.IMPACT);
        event.add(type, EpicFightAttributes.MAX_STRIKES);
        event.add(type, EpicFightAttributes.STUN_ARMOR);
        event.add(type, EpicFightAttributes.OFFHAND_ATTACK_SPEED);
        event.add(type, EpicFightAttributes.OFFHAND_MAX_STRIKES);
        event.add(type, EpicFightAttributes.OFFHAND_ARMOR_NEGATION);
        event.add(type, EpicFightAttributes.OFFHAND_IMPACT);
        event.add(type, EpicFightAttributes.MAX_STAMINA);
        event.add(type, EpicFightAttributes.STAMINA_REGEN);
    }
}
