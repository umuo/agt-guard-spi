package io.agt.core.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.Java8;
import io.agt.core.policy.ContentRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 验证完整响应封口、定时器调度和回调异常的资源释放边界。
 * 测试直接触发保存的任务以模拟取消后已出队的旧定时回调，不依赖等待超时。
 */
class ChunkBufferLifecycleTest {
    /**
     * 记录真实调度请求并允许手动触发旧任务，测试结束时关闭执行器。
     */
    static final class RecordingScheduler extends ScheduledThreadPoolExecutor
            implements AutoCloseable {
        private final List<Runnable> tasks = new ArrayList<>();

        RecordingScheduler() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            return super.schedule(command, delay, unit);
        }

        @Override
        public void close() {
            shutdownNow();
        }
    }

    private static ChunkBufferEngine gate(
            RecordingScheduler scheduler,
            int chunk,
            ChunkBufferEngine.Audit audit,
            Consumer<String> output,
            Consumer<Throwable> failure) {
        ContentRule rule =
                new ContentRule(
                        "local",
                        Java8.set(InterventionPoint.POST_MODEL_CALL),
                        Java8.list("SECRET"),
                        Java8.map());
        return new ChunkBufferEngine(
                new ChunkBufferOptions(chunk, "", Duration.ofSeconds(60), 128, Java8.set("local")),
                Java8.list(rule),
                scheduler,
                true,
                audit,
                output,
                failure,
                Runnable::run,
                scheduler::shutdownNow);
    }

    @Test
    void sealDenialClosesAndReportsFailureOnlyOnce() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            List<Throwable> errors = new ArrayList<>();
            StringBuilder output = new StringBuilder();
            try (ChunkBufferEngine stream =
                    gate(scheduler, 64, (r, a, b, ns) -> {}, output::append, errors::add)) {
                stream.accept("SECRET");
                GovernanceException error =
                        assertThrows(GovernanceException.class, () -> stream.seal("SECRET"));
                assertEquals("stream_content_denied", error.code());
                assertEquals(0, stream.bufferedCharacters());
                assertFalse(stream.hasScheduledCheck());
                assertTrue(scheduler.isShutdown());
                assertEquals(1, errors.size());
                stream.accept("late");
                assertThrows(GovernanceException.class, () -> stream.complete("SECRET"));
                assertEquals(1, errors.size());
                assertEquals("", output.toString());
            }
        }
    }

    @Test
    void sealAuditFailureClosesWithoutReleasingTail() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            AtomicInteger errors = new AtomicInteger();
            StringBuilder output = new StringBuilder();
            try (ChunkBufferEngine stream =
                    gate(
                            scheduler,
                            64,
                            (r, a, b, ns) -> {
                                throw new GovernanceException("audit_failed");
                            },
                            output::append,
                            error -> errors.incrementAndGet())) {
                stream.accept("safe");
                assertThrows(GovernanceException.class, () -> stream.seal("safe"));
                assertEquals(0, stream.bufferedCharacters());
                assertEquals(1, errors.get());
                assertTrue(scheduler.isShutdown());
                assertEquals("", output.toString());
            }
        }
    }

    @Test
    void sealMismatchUsesSameSingleFailureLifecycle() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            AtomicInteger errors = new AtomicInteger();
            try (ChunkBufferEngine stream =
                    gate(
                            scheduler,
                            64,
                            (r, a, b, ns) -> {},
                            text -> {},
                            error -> errors.incrementAndGet())) {
                stream.accept("safe");
                assertThrows(GovernanceException.class, () -> stream.seal("changed"));
                assertEquals(0, stream.bufferedCharacters());
                assertEquals(1, errors.get());
                assertTrue(scheduler.isShutdown());
                stream.close();
                assertEquals(1, errors.get());
            }
        }
    }

    @Test
    void largeCallbackSchedulesOnlyItsUncheckedRemainder() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            StringBuilder output = new StringBuilder();
            try (ChunkBufferEngine stream =
                    gate(
                            scheduler,
                            64,
                            (r, a, b, ns) -> {},
                            output::append,
                            error -> {
                                throw new AssertionError(error);
                            })) {
                String text = Java8.repeat("a", 64 * 256);
                stream.accept(text);
                assertEquals(0, scheduler.tasks.size());
                stream.accept("abc");
                assertEquals(1, scheduler.tasks.size());
                assertTrue(stream.hasScheduledCheck());
                stream.seal(text + "abc");
                stream.complete(text + "abc");
                assertEquals(text + "abc", output.toString());
            }
        }
    }

    @Test
    void newCallbacksDoNotPostponeExistingDeadlineAndOldTasksAreIgnored() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            StringBuilder output = new StringBuilder();
            AtomicInteger checks = new AtomicInteger();
            try (ChunkBufferEngine stream =
                    gate(
                            scheduler,
                            64,
                            (r, a, b, ns) -> checks.incrementAndGet(),
                            output::append,
                            error -> {
                                throw new AssertionError(error);
                            })) {
                stream.accept("abc");
                stream.accept("def");
                assertEquals(1, scheduler.tasks.size());
                scheduler.tasks.get(0).run();
                assertEquals("a", output.toString());
                assertEquals(1, checks.get());
                stream.accept("g");
                assertEquals(2, scheduler.tasks.size());
                scheduler.tasks.get(0).run();
                assertEquals(1, checks.get());
                scheduler.tasks.get(1).run();
                assertEquals("ab", output.toString());
                stream.seal("abcdefg");
                stream.complete("abcdefg");
                assertEquals("abcdefg", output.toString());
            }
        }
    }

    @Test
    void cancelledTimerCannotReleaseAfterClose() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            AtomicInteger emitted = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            try (ChunkBufferEngine stream =
                    gate(
                            scheduler,
                            64,
                            (r, a, b, ns) -> {},
                            text -> emitted.incrementAndGet(),
                            error -> errors.incrementAndGet())) {
                stream.accept("abcdefgh");
                Runnable old = scheduler.tasks.get(0);
                stream.close();
                old.run();
                stream.accept("late");
                stream.close();
                assertEquals(0, emitted.get());
                assertEquals(0, errors.get());
                assertEquals(0, stream.bufferedCharacters());
                assertTrue(scheduler.isShutdown());
            }
        }
    }

    @Test
    void reentrantAuditClosePreventsRelease() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            AtomicReference<ChunkBufferEngine> current = new AtomicReference<>();
            AtomicInteger emitted = new AtomicInteger();
            try (ChunkBufferEngine stream =
                    gate(
                            scheduler,
                            8,
                            (r, a, b, ns) -> current.get().close(),
                            text -> emitted.incrementAndGet(),
                            error -> {})) {
                current.set(stream);
                stream.accept("abcdefgh");
                assertEquals(0, emitted.get());
                assertEquals(0, stream.bufferedCharacters());
                assertTrue(scheduler.isShutdown());
            }
        }
    }

    @Test
    void outputFailureClearsResourcesAndDoesNotNotifyTwice() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            AtomicInteger errors = new AtomicInteger();
            try (ChunkBufferEngine stream =
                    gate(
                            scheduler,
                            8,
                            (r, a, b, ns) -> {},
                            text -> {
                                throw new IllegalStateException("consumer failed");
                            },
                            error -> errors.incrementAndGet())) {
                stream.accept("abcdefgh");
                stream.accept("late");
                assertEquals(1, errors.get());
                assertEquals(0, stream.bufferedCharacters());
                assertFalse(stream.hasScheduledCheck());
                assertTrue(scheduler.isShutdown());
            }
        }
    }

    @Test
    void rejectedTimerSchedulingFailsClosed() {
        try (RecordingScheduler scheduler = new RecordingScheduler()) {
            AtomicInteger errors = new AtomicInteger();
            try (ChunkBufferEngine stream =
                    gate(
                            scheduler,
                            64,
                            (r, a, b, ns) -> {},
                            text -> {},
                            error -> errors.incrementAndGet())) {
                scheduler.shutdownNow();
                stream.accept("short");
                assertEquals(1, errors.get());
                assertEquals(0, stream.bufferedCharacters());
                assertFalse(stream.hasScheduledCheck());
            }
        }
    }
}
