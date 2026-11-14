package com.pla.smart_npc.ai;

import java.util.Objects;
import java.util.UUID;

/** No entity, world, inventory, or other mutable game reference may cross this boundary. */
public record AiRequest(UUID eventId, UUID npcId, UUID playerId, String systemPrompt, String contextJson, int priority) {
    /** Spoken danger reactions must not starve a player waiting for an answer. */
    public int schedulingPriority() { return priority == 1 && playerId != null ? -1 : priority; }

    public AiRequest {
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(npcId);
        Objects.requireNonNull(systemPrompt);
        Objects.requireNonNull(contextJson);
        if (systemPrompt.length() > 6000 || contextJson.length() > 10000 || priority < 0 || priority > 2) {
            throw new IllegalArgumentException("AI request exceeds its bounded prompt or priority limits");
        }
    }
}
