package com.pla.smart_npc.ai;

/** A proposal only: the game-thread guard remains the authority for every action. */
public record AiAction(Type type, String target, String item, int count) {
    public enum Type {
        NONE, FOLLOW_PLAYER, STOP_CURRENT_GOAL, RESUME_PREVIOUS_GOAL,
        GATHER_ITEM, MINE_RESOURCE, GIVE_ITEM, ASK_PLAYER_FOR_ITEM,
        APPROACH_ENTITY, SOCIAL_LOOK_AT, REQUEST_HELP, SOCIAL_EMOTE,
        TROLL_SWING, START_NPC_CONVERSATION, ACCEPT_TEAM_INVITE, DECLINE_TEAM_INVITE
    }

    public static AiAction none() { return new AiAction(Type.NONE, null, null, 0); }
}
