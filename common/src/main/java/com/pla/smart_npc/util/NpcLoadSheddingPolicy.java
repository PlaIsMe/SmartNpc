package com.pla.smart_npc.util;

/** Pure load-shedding decisions, shared by the worker and chunk-ticket controllers. */
final class NpcLoadSheddingPolicy {
    private static final int REQUIRED_OVERLOAD_SAMPLES = 2;
    private int overloadSamples;

    /** Call once per controller evaluation, not once per NPC/goal query. */
    boolean observe(double rollingMspt, double reductionThreshold) {
        if (!Double.isFinite(rollingMspt) || rollingMspt < reductionThreshold) {
            this.overloadSamples = 0;
            return false;
        }
        this.overloadSamples = Math.min(REQUIRED_OVERLOAD_SAMPLES, this.overloadSamples + 1);
        return this.overloadSamples >= REQUIRED_OVERLOAD_SAMPLES;
    }

    void reset() {
        this.overloadSamples = 0;
    }

    /** A conservative estimate, never an assertion that all NPC cost scales with worker count. */
    static int reducedWorkerLimit(int currentLimit, int activeWorkers, double averageMspt,
                                  double npcMs, double targetMspt) {
        if (currentLimit <= 1) {
            return 1;
        }
        if (!Double.isFinite(npcMs) || !Double.isFinite(averageMspt)
                || npcMs <= 0.1D || averageMspt <= 0.0D) {
            return currentLimit - 1;
        }
        double availableNpcMs = Math.max(0.0D, targetMspt - Math.max(0.0D, averageMspt - npcMs));
        double npcMsPerWorker = npcMs / Math.max(1, activeWorkers);
        int estimate = (int) Math.floor(availableNpcMs / npcMsPerWorker);
        return Math.max(1, Math.min(currentLimit - 1, estimate));
    }
}
