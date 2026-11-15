package com.pla.smart_npc.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded, once-per-second opportunities around a rotating online listener; never loads chunks. */
final class AiSpontaneousSpeech {
    private record Pair(UUID first, UUID second) {
        static Pair of(Entity first, Entity second) {
            UUID a = first.getUUID(), b = second.getUUID();
            return a.compareTo(b) < 0 ? new Pair(a, b) : new Pair(b, a);
        }
    }
    private final AiChatService service;
    private final AiConfig config;
    private final Map<Pair, Integer> greetings = new LinkedHashMap<>();
    private final Map<String, Integer> areas = new LinkedHashMap<>();
    private int cursor;
    private int nextOpportunity;

    AiSpontaneousSpeech(AiChatService service, AiConfig config) { this.service = service; this.config = config; }

    void tick(ServerPlayer viewer, List<PlayerNpcEntity> nearby, int now) {
        greetings.entrySet().removeIf(entry -> now >= entry.getValue());
        areas.entrySet().removeIf(entry -> now >= entry.getValue());
        if ((!config.ambientChat && !config.npcConversations) || !config.eventReactions || nearby.isEmpty() || now < nextOpportunity) return;
        int start = Math.floorMod(cursor++, nearby.size());
        for (int index = 0; index < Math.min(12, nearby.size()); index++) {
            PlayerNpcEntity npc = nearby.get((start + index) % nearby.size());
            if (!service.speechAvailable(npc) || npc.tickCount < 60 || npc.getTarget() != null
                    || npc.distanceToSqr(viewer) > config.hearingRadius * config.hearingRadius) continue;
            String area = npc.level().dimension().location() + ":" + (npc.blockPosition().getX() >> 5) + ":" + (npc.blockPosition().getZ() >> 5);
            if (now < areas.getOrDefault(area, 0) || npc.getRandom().nextInt(6) != 0) continue;

            // Alternate the first choice so player greetings do not crowd out NPC-to-NPC greetings.
            boolean otherFirst = npc.getRandom().nextBoolean();
            boolean spoke = otherFirst && greetNpc(npc, nearby, start, now);
            if (!spoke && config.ambientChat) spoke = greet(npc, viewer, now);
            if (!spoke && !otherFirst) spoke = greetNpc(npc, nearby, start, now);
            if (!spoke && config.ambientChat && npc.getRandom().nextInt(3) == 0) {
                if (npc.getRandom().nextBoolean()) spoke = noticeCreature(npc);
                if (!spoke) spoke = service.spontaneousSpeech(npc, "activity_commentary", null, List.of(
                        "Current activity: " + npc.getCurrentAiState().replace("ai.player_npc.", "").replace('_', ' '),
                        "Activity detail: " + npc.getCurrentAiDetail(),
                        "Use the current world.weather and world.exposed facts for any weather remark."), false);
            }
            if (spoke) {
                nextOpportunity = now + 200; // At most one new ambient exchange per ten seconds server-wide.
                if (areas.size() >= 128) areas.remove(areas.keySet().iterator().next());
                areas.put(area, now + Math.max(200, Math.min(600, config.socialCooldownTicks / 4)));
                return;
            }
        }
    }

    private boolean noticeCreature(PlayerNpcEntity npc) {
        // One local query only after a random speech opportunity; bound line-of-sight checks.
        for (LivingEntity creature : npc.level().getEntitiesOfClass(LivingEntity.class, npc.getBoundingBox().inflate(8),
                entity -> entity != npc && entity.isAlive() && !(entity instanceof PlayerNpcEntity) && !(entity instanceof Player)).stream().limit(4).toList()) {
            if (npc.distanceToSqr(creature) <= 64 && npc.hasLineOfSight(creature)) {
                service.interactions().observe(npc, creature);
                return service.spontaneousSpeech(npc, "notice_creature", creature, List.of(
                        "Visible creature: " + creature.getName().getString(), "You can see this creature nearby. No attack has been observed in this event.", "Creature coordinates: " + creature.blockPosition().toShortString()), false);
            }
        }
        return false;
    }

    private boolean greetNpc(PlayerNpcEntity npc, List<PlayerNpcEntity> nearby, int start, int now) {
        if (!config.npcConversations) return false;
        for (int index = 0; index < Math.min(12, nearby.size()); index++) {
            PlayerNpcEntity other = nearby.get((start + index) % nearby.size());
            if (other != npc && other.getTarget() == null && service.speechAvailable(other) && npc.distanceToSqr(other) <= 64
                    && greet(npc, other, now)) return true;
        }
        return false;
    }

    private boolean greet(PlayerNpcEntity npc, Entity other, int now) {
        Pair pair = Pair.of(npc, other);
        if (now < greetings.getOrDefault(pair, 0) || !npc.hasLineOfSight(other)) return false;
        boolean isNpc = other instanceof PlayerNpcEntity;
        List<String> facts = new ArrayList<>(List.of(
                "You notice " + other.getName().getString() + " nearby and can see them.",
                "Initiate a short greeting or casual comment. They have not said anything to you. Do not assume you are friends."));
        if (other instanceof PlayerNpcEntity otherNpc && otherNpc.isYsmActive()) facts.add("Visible YSM model on " + other.getName().getString() + ": " + otherNpc.getYsmModelId());
        if (!service.spontaneousSpeech(npc, isNpc ? "notice_npc" : "notice_player", other, facts, isNpc)) return false;
        if (greetings.size() >= 512) greetings.remove(greetings.keySet().iterator().next());
        greetings.put(pair, now + Math.max(6000, config.socialCooldownTicks));
        return true;
    }

    void forget(UUID id) { greetings.keySet().removeIf(pair -> pair.first().equals(id) || pair.second().equals(id)); }
    void clear() { greetings.clear(); areas.clear(); nextOpportunity = 0; }
}
