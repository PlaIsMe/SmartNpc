package com.pla.smart_npc.ai;

/** Blocking provider methods are invoked exclusively by the runtime's single worker. */
public interface AiInferenceProvider extends AutoCloseable {
    void start() throws Exception;
    String generate(AiRequest request) throws Exception;
    @Override void close();
}
