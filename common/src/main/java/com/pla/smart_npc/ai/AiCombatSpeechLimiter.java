package com.pla.smart_npc.ai;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread combat speech budget, shared by every dimension and NPC. No deferred backlog. */
final class AiCombatSpeechLimiter {
    private static final int GAP_TICKS = 200;
    private static final int WINDOW_TICKS = 1200;
    private static final int NPC_GAP_TICKS = 900;
    private static final int MAX_PER_WINDOW = 3;
    private final ArrayDeque<Integer> admissions = new ArrayDeque<>();
    private final ArrayDeque<Integer> deliveries = new ArrayDeque<>();
    private final Map<UUID, Integer> npcAdmissions = new LinkedHashMap<>();

    static boolean applies(String event) {
        return switch (event) {
            case "hurt_reaction", "low_health", "npc_died", "warn_death", "death_reaction", "killer_taunt", "call_help", "bow_aimed", "battle_plea" -> true;
            default -> event.startsWith("witness_");
        };
    }

    boolean tryAdmit(UUID npc, int now) {
        prune(admissions, now);
        npcAdmissions.entrySet().removeIf(entry -> now - entry.getValue() >= NPC_GAP_TICKS);
        if (!available(admissions, now) || npcAdmissions.containsKey(npc)) return false;
        admissions.addLast(now);
        if (npcAdmissions.size() >= 256) npcAdmissions.remove(npcAdmissions.keySet().iterator().next());
        npcAdmissions.put(npc, now);
        return true;
    }

    /** Check actual output too: slow inference must not bunch previously admitted lines together. */
    boolean tryDeliver(int now) {
        prune(deliveries, now);
        if (!available(deliveries, now)) return false;
        deliveries.addLast(now);
        return true;
    }

    private static boolean available(ArrayDeque<Integer> times, int now) {
        return times.size() < MAX_PER_WINDOW && (times.isEmpty() || now - times.peekLast() >= GAP_TICKS);
    }
    private static void prune(ArrayDeque<Integer> times, int now) {
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_TICKS) times.removeFirst();
    }
    void clear() { admissions.clear(); deliveries.clear(); npcAdmissions.clear(); }
}
