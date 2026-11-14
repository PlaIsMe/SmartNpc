package com.pla.smart_npc.client.compat;

/** Timing shared by the NPC body and held-item pose samples. */
final class NpcAnimationTimeline {
    private NpcAnimationTimeline() {
    }

    static float poseTime(Object animation, float elapsed) throws ReflectiveOperationException {
        Class<?> type = animation.getClass();
        // JSON uses returnTick; the parsed PlayerAnimator field is returnToTick.
        // Read the actual fields so an incompatible API fails through the
        // bridge's optional-mod fallback instead of silently restarting poses.
        return poseTime(elapsed,
                type.getField("returnToTick").getInt(animation),
                type.getField("endTick").getInt(animation),
                type.getField("stopTick").getInt(animation),
                type.getField("isInfinite").getBoolean(animation));
    }

    static float poseTime(float elapsed, int returnTick, int endTick, int stopTick, boolean looping) {
        elapsed = Math.max(0.0F, elapsed);
        if (!looping) {
            return Math.min(elapsed, Math.max(0, stopTick));
        }
        // PlayerAnimator loops AFTER endTick, so both endpoints are inclusive.
        int loopLength = endTick - returnTick + 1;
        if (loopLength <= 0) {
            return Math.max(0, returnTick);
        }
        if (elapsed < endTick + 1.0F) {
            return elapsed;
        }
        return returnTick + (elapsed - returnTick) % loopLength;
    }
}
