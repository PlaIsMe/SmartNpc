package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.init.SmartNpcModEntities;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import yesman.epicfight.api.forgeevent.EntityPatchRegistryEvent;
import yesman.epicfight.world.entity.ai.attribute.EpicFightAttributes;

public final class EpicFightSmartNpcPatches {
    private EpicFightSmartNpcPatches() {
    }

    @SubscribeEvent
    public static void setPatch(EntityPatchRegistryEvent event) {
        event.getTypeEntry().put(SmartNpcModEntities.PLAYER_NPC.get(), entity -> AdvancedPlayerNpcPatch::new);
    }


    @SubscribeEvent
    public static void addEpicFightAttributes(EntityAttributeModificationEvent event) {
        var type = SmartNpcModEntities.PLAYER_NPC.get();
        event.add(type, EpicFightAttributes.WEIGHT.get());
        event.add(type, EpicFightAttributes.ARMOR_NEGATION.get());
        event.add(type, EpicFightAttributes.IMPACT.get());
        event.add(type, EpicFightAttributes.MAX_STRIKES.get());
        event.add(type, EpicFightAttributes.STUN_ARMOR.get());
        event.add(type, EpicFightAttributes.OFFHAND_ATTACK_SPEED.get());
        event.add(type, EpicFightAttributes.OFFHAND_MAX_STRIKES.get());
        event.add(type, EpicFightAttributes.OFFHAND_ARMOR_NEGATION.get());
        event.add(type, EpicFightAttributes.OFFHAND_IMPACT.get());
        event.add(type, EpicFightAttributes.MAX_STAMINA.get());
        event.add(type, EpicFightAttributes.STAMINA_REGEN.get());
    }
}
