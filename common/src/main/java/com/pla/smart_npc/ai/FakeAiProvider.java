package com.pla.smart_npc.ai;

/** Explicit development mode only; never selected as a silent fallback for a missing model. */
public final class FakeAiProvider implements AiInferenceProvider {
    @Override public void start() {}
    @Override public String generate(AiRequest request) {
        return "{\"say\":\"Hello! This is the AI connection test.\",\"emotion\":\"neutral\",\"action\":{\"type\":\"NONE\",\"target\":null,\"item\":null,\"count\":0}}";
    }
    @Override public void close() {}
}
