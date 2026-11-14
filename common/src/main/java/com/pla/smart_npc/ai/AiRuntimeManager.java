package com.pla.smart_npc.ai;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** One actor owns startup, one inference at a time, and provider teardown. No tick-thread waiting. */
public final class AiRuntimeManager implements AutoCloseable {
    private final AiConfig config;
    private final Supplier<AiInferenceProvider> factory;
    private final List<Pending> queue = new ArrayList<>();
    private final CompletableFuture<Void> startup = new CompletableFuture<>();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private volatile boolean ready;
    private volatile boolean closed;
    private volatile String state = "stopped";
    private Thread worker;
    private Pending active;
    private long sequence;
    private long completed;
    private long rejected;
    private long failures;
    private long elapsedNanos;

    public AiRuntimeManager(Path directory, AiConfig config) {
        this(config, () -> config.fakeProvider ? new FakeAiProvider() : config.externalRuntime ? new LlamaCppProvider(directory, config) : new EmbeddedLlamaProvider(directory, config));
    }

    /** Provider injection supports lifecycle/queue verification without native binaries. */
    public AiRuntimeManager(AiConfig config, Supplier<AiInferenceProvider> factory) {
        config.validate();
        this.config = config;
        this.factory = factory;
    }

    public synchronized CompletableFuture<Void> start() {
        if (worker != null || closed) return startup;
        if (!config.enabled) {
            startup.completeExceptionally(new IllegalStateException("Legacy chat selected; native runtime is disabled"));
            return startup;
        }
        state = "starting";
        worker = new Thread(this::run, "SmartNpc-AI-inference");
        worker.setDaemon(true);
        worker.start();
        return startup;
    }

    public synchronized CompletableFuture<AiResponse> submit(AiRequest request) {
        if (!ready || closed) return CompletableFuture.failedFuture(new RejectedExecutionException("AI provider is not ready"));
        long now = System.nanoTime();
        queue.removeIf(pending -> {
            if (pending.deadline() > now && !pending.result().isCancelled()) return false;
            pending.result().completeExceptionally(new TimeoutException("AI request expired in queue"));
            rejected++;
            return true;
        });
        if (queue.size() >= config.queueCapacity) {
            Pending worst = queue.stream().max(Comparator.comparingInt((Pending p) -> p.request().schedulingPriority()).thenComparingLong(Pending::sequence)).orElseThrow();
            if (worst.request().schedulingPriority() <= request.schedulingPriority()) {
                rejected++;
                return CompletableFuture.failedFuture(new RejectedExecutionException("AI queue is full"));
            }
            queue.remove(worst);
            worst.result().completeExceptionally(new RejectedExecutionException("AI ambient request displaced by higher priority speech"));
            rejected++;
        }
        CompletableFuture<AiResponse> result = new CompletableFuture<>();
        result.whenComplete((reply, error) -> {
            if (!result.isCancelled()) return;
            synchronized (this) {
                // HTTP and embedded JNI both observe this actor's interruption.
                // Match the exact active result so cancellation cannot hit another NPC.
                if (active != null && active.result() == result && worker != null) worker.interrupt();
                notifyAll();
            }
        });
        // A direct question must survive one in-flight generation too. Larger CPU models
        // routinely exceed the ambient five-second deadline before they can take it.
        // Delivery still revalidates the original conversation's bounded lifetime.
        long queueSeconds = request.priority() <= 1 ? config.queueExpirySeconds + config.inferenceTimeoutSeconds : config.queueExpirySeconds;
        queue.add(new Pending(request, now + TimeUnit.SECONDS.toNanos(queueSeconds), sequence++, result));
        notifyAll();
        return result;
    }

    private void run() {
        AiInferenceProvider provider = null;
        try {
            provider = factory.get();
            provider.start();
            synchronized (this) {
                if (closed) return;
                ready = true;
                state = config.fakeProvider ? "ready (FAKE development provider)" : "ready: " + config.modelId;
                startup.complete(null);
            }
            while (!closed) {
                Pending pending;
                synchronized (this) {
                    while (queue.isEmpty() && !closed) wait();
                    if (closed) break;
                    queue.sort(Comparator.comparingInt((Pending p) -> p.request().schedulingPriority()).thenComparingLong(Pending::sequence));
                    pending = queue.remove(0);
                    if (pending.result().isCancelled() || System.nanoTime() > pending.deadline()) {
                        pending.result().completeExceptionally(new TimeoutException("AI request expired in queue"));
                        rejected++;
                        continue;
                    }
                    active = pending;
                }
                long start = System.nanoTime();
                try {
                    AiResponse reply = AiResponseDecoder.decode(provider.generate(pending.request()));
                    synchronized (this) {
                        if (!closed && !pending.result().isCancelled()) {
                            pending.result().complete(reply);
                            completed++;
                            elapsedNanos += System.nanoTime() - start;
                        }
                    }
                } catch (InterruptedException exception) {
                    synchronized (this) {
                        if (closed || !pending.result().isCancelled()) {
                            pending.result().completeExceptionally(new CancellationException("AI inference cancelled"));
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // Only this request was replaced. Keep the loaded model and actor.
                    }
                } catch (Exception exception) {
                    synchronized (this) { if (!pending.result().isCancelled()) failures++; }
                    pending.result().completeExceptionally(exception);
                } finally {
                    synchronized (this) {
                        active = null;
                        // Cancellation and this clear share the same lock. A late cancel
                        // cannot leak its interrupt into the next request or actor wait.
                        if (!closed) Thread.interrupted();
                    }
                }
            }
        } catch (Exception exception) {
            state = "failed: " + safeError(exception);
            startup.completeExceptionally(exception);
        } finally {
            ready = false;
            RuntimeException shutdownFailure = null;
            try {
                if (provider != null) provider.close();
            } catch (RuntimeException exception) {
                shutdownFailure = exception;
                state = "provider shutdown failed: " + safeError(exception);
            }
            synchronized (this) {
                closed = true;
                for (Pending pending : queue) pending.result().cancel(false);
                queue.clear();
                if (!startup.isDone()) startup.completeExceptionally(new CancellationException("AI startup cancelled"));
            }
            if (shutdownFailure == null) termination.complete(null);
            else termination.completeExceptionally(shutdownFailure);
        }
    }

    public boolean isReady() { return ready && !closed; }
    public CompletableFuture<Void> termination() { return termination; }
    public String settingsStatus() {
        if (state.startsWith("failed:") || state.startsWith("provider shutdown failed:")) return "smart_npc.ai.start_failed";
        return !ready && !closed && config.enabled ? "smart_npc.ai.starting" : "";
    }
    public synchronized String status() {
        long averageMs = completed == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos / completed);
        return state + "; queued=" + queue.size() + "/" + config.queueCapacity + "; busy=" + (active != null) + "; replies=" + completed + "; dropped=" + rejected + "; failures=" + failures + "; meanMs=" + averageMs;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        ready = false;
        state = "stopped";
        for (Pending pending : queue) pending.result().cancel(false);
        queue.clear();
        if (active != null) active.result().cancel(false);
        if (worker != null) worker.interrupt();
        else {
            startup.completeExceptionally(new CancellationException("AI runtime closed"));
            termination.complete(null);
        }
        notifyAll();
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null) return exception.getClass().getSimpleName();
        return message.replaceAll("[\\p{Cntrl}§]", " ").substring(0, Math.min(message.length(), 180));
    }

    private record Pending(AiRequest request, long deadline, long sequence, CompletableFuture<AiResponse> result) {}
}
