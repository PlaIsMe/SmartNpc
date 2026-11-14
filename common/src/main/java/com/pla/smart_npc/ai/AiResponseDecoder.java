package com.pla.smart_npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** Reject malformed, extra-key, duplicate-key, or coercible model output; never evaluate text. */
public final class AiResponseDecoder {
    private AiResponseDecoder() {}

    public static AiResponse decode(String text) {
        if (text == null || text.length() > 4096) throw new IllegalArgumentException("AI reply too large");
        try {
            // Gson's tree parser accepts duplicate keys and some nonstandard JSON. Check the wire first.
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(false);
            checkValue(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing AI reply data");
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            exactKeys(root, Set.of("say", "emotion", "action"));
            String say = cleanSpeech(string(root.get("say"), 100, false));
            String emotion = string(root.get("emotion"), 24, false);
            if (!emotion.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid emotion");
            JsonObject action = root.getAsJsonObject("action");
            exactKeys(action, Set.of("type", "target", "item", "count"));
            AiAction.Type type = AiAction.Type.valueOf(string(action.get("type"), 32, false));
            String target = string(action.get("target"), 64, true);
            String item = string(action.get("item"), 120, true);
            if (target != null && !target.equals("PLAYER") && !target.equals("NPC") && !target.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid target");
            if (item != null && !item.matches(type == AiAction.Type.GIVE_ITEM ? "(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+" : "[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Invalid item ID/query");
            JsonElement countElement = action.get("count");
            if (!countElement.isJsonPrimitive() || !countElement.getAsJsonPrimitive().isNumber() || !countElement.toString().matches("0|[1-9][0-9]?")) throw new IllegalArgumentException("Invalid count");
            int count = countElement.getAsInt();
            if (count > 64) throw new IllegalArgumentException("Count exceeds 64");
            if ((type == AiAction.Type.NONE || AiTeamInvitations.decision(type)) && (target != null || item != null || count != 0)) throw new IllegalArgumentException("Speech/team decisions must not carry action arguments");
            if (type == AiAction.Type.GIVE_ITEM && item == null) throw new IllegalArgumentException("Gift requires an item ID/query");
            if ((type == AiAction.Type.ASK_PLAYER_FOR_ITEM || type == AiAction.Type.GATHER_ITEM || type == AiAction.Type.MINE_RESOURCE) && (item == null || count < 1)) throw new IllegalArgumentException("Item action requires item and positive count");
            return new AiResponse(say, emotion, new AiAction(type, target, item, count));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI response contract", exception);
        }
    }

    private static String cleanSpeech(String text) {
        String result = text.strip();
        // Clean only speech after strict JSON validation; preserve quoted words inside a sentence.
        while (result.length() >= 2 && result.startsWith("\"") && result.endsWith("\"")) result = result.substring(1, result.length() - 1).strip();
        if (result.chars().filter(c -> c == '"').count() % 2 != 0) {
            if (result.endsWith("\"")) result = result.substring(0, result.length() - 1).strip();
            else if (result.startsWith("\"")) result = result.substring(1).strip();
        }
        if (result.isBlank()) throw new IllegalArgumentException("Empty AI speech");
        return result;
    }

    private static String string(JsonElement value, int limit, boolean nullable) {
        if (nullable && value != null && value.isJsonNull()) return null;
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        String text = value.getAsString();
        if (text.isBlank() || text.length() > limit || text.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0xA7 || Character.getType(c) == Character.FORMAT)) throw new IllegalArgumentException("Unsafe or oversized text");
        return text;
    }

    private static void exactKeys(JsonObject object, Set<String> keys) {
        if (object == null || !object.keySet().equals(keys)) throw new IllegalArgumentException("Unexpected reply fields");
    }

    private static void checkValue(JsonReader reader, int depth) throws Exception {
        if (depth > 4) throw new IllegalArgumentException("AI JSON is nested too deeply");
        switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                Set<String> keys = new HashSet<>();
                while (reader.hasNext()) {
                    if (!keys.add(reader.nextName()) || keys.size() > 8) throw new IllegalArgumentException("Duplicate/excess JSON keys");
                    checkValue(reader, depth + 1);
                }
                reader.endObject();
            }
            case STRING, NUMBER -> reader.nextString();
            case NULL -> reader.nextNull();
            default -> throw new IllegalArgumentException("Unsupported JSON value");
        }
    }

    public static JsonObject schema() {
        JsonObject schema = JsonParser.parseString("""
                {"type":"object","additionalProperties":false,"required":["say","emotion","action"],"properties":{
                  "say":{"type":"string","minLength":1,"maxLength":100},
                  "emotion":{"type":"string","pattern":"^[a-z_]+$","maxLength":24},
                  "action":{"oneOf":[
                    {"type":"object","additionalProperties":false,"required":["type","target","item","count"],"properties":{
                      "type":{"enum":["NONE","ACCEPT_TEAM_INVITE","DECLINE_TEAM_INVITE"]},"target":{"type":"null"},"item":{"type":"null"},"count":{"const":0}}},
                    {"type":"object","additionalProperties":false,"required":["type","target","item","count"],"properties":{
                      "type":{"const":"GIVE_ITEM"},"target":{"const":"PLAYER"},
                      "item":{"type":"string","pattern":"^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$","maxLength":120},
                      "count":{"type":"integer","minimum":0,"maximum":64}}},
                    {"type":"object","additionalProperties":false,"required":["type","target","item","count"],"properties":{
                      "type":{"const":"ASK_PLAYER_FOR_ITEM"},
                      "target":{"const":"PLAYER"},
                      "item":{"type":"string","pattern":"^[a-z0-9_.-]+:[a-z0-9_./-]+$","maxLength":120},
                      "count":{"type":"integer","minimum":1,"maximum":64}}},
                    {"type":"object","additionalProperties":false,"required":["type","target","item","count"],"properties":{
                      "type":{"enum":["GATHER_ITEM","MINE_RESOURCE"]},"target":{"type":"null"},
                      "item":{"type":"string","pattern":"^[a-z0-9_.-]+:[a-z0-9_./-]+$","maxLength":120},
                      "count":{"type":"integer","minimum":1,"maximum":64}}},
                    {"type":"object","additionalProperties":false,"required":["type","target","item","count"],"properties":{
                      "type":{"enum":["FOLLOW_PLAYER","STOP_CURRENT_GOAL","RESUME_PREVIOUS_GOAL","APPROACH_ENTITY","SOCIAL_LOOK_AT","REQUEST_HELP","SOCIAL_EMOTE","TROLL_SWING","START_NPC_CONVERSATION"]},
                      "target":{"enum":["PLAYER","NPC",null]},"item":{"type":"null"},"count":{"const":0}}}
                  ]}}}
                """).getAsJsonObject();
        return schema;
    }

    /** Constrain generation to the snapshot's capabilities; the game thread must still revalidate. */
    public static JsonObject schema(String contextJson) {
        Set<String> allowed = new HashSet<>();
        allowed.add("NONE");
        JsonObject context = JsonParser.parseString(contextJson).getAsJsonObject();
        if (context.has("allowed_actions")) {
            for (JsonElement action : context.getAsJsonArray("allowed_actions")) allowed.add(action.getAsString());
        }
        JsonObject schema = schema();
        JsonObject action = schema.getAsJsonObject("properties").getAsJsonObject("action");
        JsonArray kept = new JsonArray();
        for (JsonElement branch : action.getAsJsonArray("oneOf")) {
            JsonObject type = branch.getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("type");
            if (type.has("const")) {
                if (allowed.contains(type.get("const").getAsString())) kept.add(branch);
            } else {
                JsonArray choices = new JsonArray();
                for (JsonElement choice : type.getAsJsonArray("enum")) if (allowed.contains(choice.getAsString())) choices.add(choice);
                if (!choices.isEmpty()) {
                    type.add("enum", choices);
                    kept.add(branch);
                }
            }
        }
        action.add("oneOf", kept);
        return schema;
    }
}
