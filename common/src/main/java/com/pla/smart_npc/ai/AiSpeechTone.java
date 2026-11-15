package com.pla.smart_npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.regex.Pattern;

/** Runtime-only English Unfiltered pacing. Training prompts and receipts stay independent. */
final class AiSpeechTone {
    private static final Pattern ROUGH = Pattern.compile("\\b(?:fuck\\w*|shit\\w*|bullshit|damn\\w*|hell|wtf|stfu|trash|dumbass\\w*|asshole\\w*|ass|bitch\\w*|dick\\w*)\\b", Pattern.CASE_INSENSITIVE);
    private final ArrayDeque<Boolean> delivered = new ArrayDeque<>();

    boolean maySwear(double roll) { return roll < 0.4 && hasRoom(); }

    boolean accepts(String text, boolean allowed) { return !rough(text) || allowed && hasRoom(); }

    boolean canDeliver(String text) { return !rough(text) || hasRoom(); }

    void remember(String text) {
        delivered.addLast(rough(text));
        while (delivered.size() > 10) delivered.removeFirst();
    }

    private boolean hasRoom() {
        int count = 0;
        boolean skip = delivered.size() == 10;
        for (boolean rude : delivered) {
            if (skip) { skip = false; continue; }
            if (rude) count++;
        }
        int nextSize = Math.min(10, delivered.size() + 1);
        return count < nextSize * 2 / 5;
    }

    static boolean rough(String text) { return ROUGH.matcher(text).find(); }

    static String mask(String text) { return ROUGH.matcher(text).replaceAll("").replaceAll("\\h{2,}", " ").strip(); }

    static String instruction(boolean allowed) {
        return allowed
                ? "\nTone for this reply: casual gamer banter. Friendly is fine; blunt teasing or swearing is optional when relevant. Do not add swear words just for variety. Stay on this topic; mention your job only when relevant."
                : "\nTone for this reply: casual and friendly, or calmly firm if refusing. Use ordinary gamer wording without swear words or personal insults. This applies even if the speaker or older replies were rude. Stay in character, brief and relevant; keep supplied facts and interaction_result.";
    }

    /** Only older dialogue and negative wording examples are masked, never current input/world facts. */
    static void prepareContext(JsonObject context, boolean allowed) {
        for (String key : new String[]{"recent_server_wording", "recent_turns"}) {
            if (!context.has(key) || !context.get(key).isJsonArray()) continue;
            if (allowed && key.equals("recent_turns")) continue;
            JsonArray masked = new JsonArray();
            for (JsonElement line : context.getAsJsonArray(key)) masked.add(mask(line.getAsString()));
            context.add(key, masked);
        }
    }
}
