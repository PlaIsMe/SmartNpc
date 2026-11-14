package com.pla.smart_npc.ai;

/** Optional English release style; language, action authorization and safety facts stay independent. */
public final class AiSpeechStyle {
    private AiSpeechStyle() {}
    public static boolean unfiltered(String id) { return id != null && id.endsWith("-en_us-unfiltered"); }
    public static String apply(String prompt, String id) {
        if (!unfiltered(id)) return prompt;
        return prompt.replace("Never swear. Blunt banter is fine; greetings are usually friendly.", "Mix casual, friendly gamer chat with blunt trash talk. Swearing is allowed; don't force it.")
                .replace("Casual humor is welcome. Never swear; ordinary greetings are usually friendly.", "Mix friendly gamer slang with blunt trash talk. Swearing is allowed; don't force it.");
    }
}
