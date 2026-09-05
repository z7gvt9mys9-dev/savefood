package ru.savefood.match;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class BoundedWorkExecutorTest {
    @Test
    void hundredsOfSubmissionsStayBoundedAndNeverRunOnCaller() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        List<Thread> workers = new CopyOnWriteArrayList<>();
        BoundedWorkExecutor executor = new BoundedWorkExecutor("bounded-test",
            new MatchingWorkProperties.ExecutorLimits(2, 3));
        try {
            Runnable slow = () -> {
                workers.add(Thread.currentThread());
                started.countDown();
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            };
            assertThat(executor.tryExecute(slow)).isTrue();
            assertThat(executor.tryExecute(slow)).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 500; i++) {
                assertThat(executor.tryExecute(slow)).isEqualTo(i < 3);
                assertThat(executor.queueDepth()).isLessThanOrEqualTo(3);
            }
            assertThat(executor.largestPoolSize()).isEqualTo(2);
            assertThat(executor.rejectedCount()).isEqualTo(497);
            assertThat(workers).doesNotContain(caller);
        } finally { executor.close(); }
        assertThat(executor.isTerminated()).isTrue();
        assertThat(executor.queueDepth()).isZero();
        assertThat(executor.tryExecute(() -> { throw new AssertionError(); })).isFalse();
    }
    @Test
    void dropOldestKeepsNewestQueuedTaskDeterministically() throws Exception {
        var limits = new MatchingWorkProperties.ExecutorLimits(1, 1);
        limits.setRejection(BoundedWorkExecutor.Rejection.DROP_OLDEST);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        List<Integer> ran = new CopyOnWriteArrayList<>();
        try (var executor = new BoundedWorkExecutor("oldest-test", limits)) {
            executor.tryExecute(() -> {
                try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            executor.tryExecute(() -> ran.add(1));
            executor.tryExecute(() -> { ran.add(2); completed.countDown(); });
            assertThat(executor.queueDepth()).isEqualTo(1);
            assertThat(executor.rejectedCount()).isEqualTo(1);
            release.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ran).containsExactly(2);
        }
    }
    @Test
    void springClosesAllThreeExecutors() {
        var context = new AnnotationConfigApplicationContext(MatchingWorkProperties.class, MatchingWorkConfiguration.class);
        var executors = context.getBeansOfType(BoundedWorkExecutor.class).values();
        assertThat(executors).hasSize(3);
        executors.forEach(executor -> executor.tryExecute(() -> {}));
        context.close();
        assertThat(executors).allMatch(BoundedWorkExecutor::isTerminated);
    }
}
