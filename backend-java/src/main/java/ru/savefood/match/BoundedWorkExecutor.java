package ru.savefood.match;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Nonblocking best-effort admission. No caller-runs or auxiliary threads. */
public final class BoundedWorkExecutor implements AutoCloseable {
    public enum Rejection { DROP_NEWEST, DROP_OLDEST }
    private final ThreadPoolExecutor pool;
    private final Rejection rejection;
    private final LongAdder rejected = new LongAdder();
    public BoundedWorkExecutor(String name, MatchingWorkProperties.ExecutorLimits limits) {
        if (limits.getWorkers() < 1 || limits.getQueueCapacity() < 1 || limits.getRejection() == null) {
            throw new IllegalArgumentException("Invalid executor limits for " + name);
        }
        rejection = limits.getRejection();
        AtomicInteger sequence = new AtomicInteger();
        pool = new ThreadPoolExecutor(limits.getWorkers(), limits.getWorkers(), 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(limits.getQueueCapacity()), task -> {
                Thread thread = new Thread(task, name + "-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized boolean tryExecute(Runnable task) {
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException full) {
            rejected.increment();
            if (!pool.isShutdown() && rejection == Rejection.DROP_OLDEST) {
                pool.getQueue().poll();
                pool.execute(task); // Admission and close are serialized; workers only remove queued tasks.
                return true;
            }
            return false;
        }
    }
    public int queueDepth() { return pool.getQueue().size(); }
    public int largestPoolSize() { return pool.getLargestPoolSize(); }
    public long rejectedCount() { return rejected.sum(); }
    public boolean isTerminated() { return pool.isTerminated(); }
    @Override
    public void close() {
        synchronized (this) { pool.shutdownNow(); }
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
