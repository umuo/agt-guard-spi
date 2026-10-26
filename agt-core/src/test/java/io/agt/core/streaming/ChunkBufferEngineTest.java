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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证流式文本跨片段匹配、有界缓存及终止语义。
 */
class ChunkBufferEngineTest {
    // Deterministic clock: tasks run only when the test advances it.
    /**
     * 由测试显式推进的确定性调度器。
     */
    static class Clock extends ScheduledThreadPoolExecutor implements AutoCloseable {
        final List<Runnable> tasks = new ArrayList<>();

        Clock() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            FutureTask<Void> future = new FutureTask<Void>(task, null);
            tasks.add(future);
            return new ScheduledFuture<Object>() {
                public long getDelay(TimeUnit u) {
                    return 0;
                }

                public int compareTo(Delayed d) {
                    return 0;
                }

                public boolean cancel(boolean b) {
                    return future.cancel(b);
                }

                public boolean isCancelled() {
                    return future.isCancelled();
                }

                public boolean isDone() {
                    return future.isDone();
                }

                public Object get() throws InterruptedException, ExecutionException {
                    return future.get();
                }

                public Object get(long n, TimeUnit u)
                        throws InterruptedException, ExecutionException, TimeoutException {
                    return future.get(n, u);
                }
            };
        }

        @Override
        public void close() {
            shutdownNow();
            tasks.clear();
        }

        void tick() {
            List<Runnable> due = Java8.copyList(tasks);
            tasks.clear();
            due.forEach(Runnable::run);
        }
    }

    static ContentRule rule(List<String> words) {
        return new ContentRule(
                "local", Java8.set(InterventionPoint.POST_MODEL_CALL), words, Java8.map());
    }

    static ChunkBufferEngine engine(
            Clock clock,
            List<String> words,
            boolean enforce,
            StringBuilder out,
            List<Throwable> errors,
            List<String> audits) {
        return new ChunkBufferEngine(
                new ChunkBufferOptions(
                        3, "，。！？\n", Duration.ofMillis(50), 4096, Java8.set("local")),
                Java8.list(rule(words)),
                clock,
                enforce,
                (id, start, end, ns) -> {
                    if (id != null) {
                        audits.add(id + ":" + start + ":" + end);
                    }
                },
                out::append,
                errors::add);
    }

    @Test
    void largeCallbacksRemainBoundedAndConcurrentTerminationIsIdempotent() throws Exception {
        ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 40; round++) {
                AtomicInteger errors = new AtomicInteger();
                AtomicInteger closes = new AtomicInteger();
                AtomicInteger emitted = new AtomicInteger();
                ChunkBufferEngine gate =
                        new ChunkBufferEngine(
                                new ChunkBufferOptions(
                                        20, "", Duration.ofMillis(1), 64, Java8.set("local")),
                                Java8.list(rule(Java8.list("ABCDEF"))),
                                clock,
                                true,
                                (r, a, b, n) -> {
                                    assertTrue(b - a <= 26);
                                },
                                t -> emitted.addAndGet(t.length()),
                                e -> errors.incrementAndGet(),
                                workers,
                                closes::incrementAndGet);
                CountDownLatch start = new CountDownLatch(1);
                Future<?> first =
                        workers.submit(
                                () -> {
                                    try {
                                        start.await();
                                        gate.accept(Java8.repeat("safe ", 1000));
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                Future<?> second =
                        workers.submit(
                                () -> {
                                    try {
                                        start.await();
                                        gate.close();
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                start.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                int before = emitted.get();
                gate.accept("late");
                gate.close();
                assertEquals(before, emitted.get());
                assertEquals(1, closes.get());
                assertTrue(errors.get() <= 1);
                assertEquals(0, gate.bufferedCharacters());
                assertFalse(gate.hasScheduledCheck());
            }
        } finally {
            workers.shutdownNow();
            clock.shutdownNow();
        }
    }

    @Test
    void rejectsInvalidConfigurationAndVocabularyBounds() {
        assertThrows(
                IllegalArgumentException.class, () -> ChunkBufferOptions.defaults(Java8.set()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkBufferOptions(0, "", Duration.ofMillis(1), 64, Java8.set("local")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkBufferOptions(2, "", Duration.ZERO, 64, Java8.set("local")));
        try (ChunkBufferEngineTest.Clock clock = new Clock()) {
            for (List<String> words :
                    Java8.list(
                            Java8.list(Java8.repeat("x", 2049)),
                            Collections.nCopies(257, "x"),
                            Collections.nCopies(33, Java8.repeat("x", 2048)))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                engine(
                                        clock,
                                        words,
                                        true,
                                        new StringBuilder(),
                                        new ArrayList<>(),
                                        new ArrayList<>()));
            }
        }
    }

    @Test
    void everySplitAndTimeoutKeepsWholeMatchPrivate() {
        for (String word :
                Java8.list("ABCDEF", "A，B。C！？\nDEF", "abcdefghijklmnopqrstuvwxyz", "😀秘密")) {
            for (int split = 0; split <= word.length(); split++) {
                try (ChunkBufferEngineTest.Clock clock = new Clock()) {
                    StringBuilder out = new StringBuilder();
                    ArrayList<Throwable> errors = new ArrayList<Throwable>();
                    try (ChunkBufferEngine gate =
                            engine(
                                    clock,
                                    Java8.list(word, "BC", "ABC"),
                                    true,
                                    out,
                                    errors,
                                    new ArrayList<>())) {
                        gate.accept("安全文本足够长安全文本足够长" + word.substring(0, split));
                        clock.tick();
                        gate.accept(word.substring(split) + "后续");
                        clock.tick();
                        assertEquals(1, errors.size(), word + "/" + split);
                        assertTrue("安全文本足够长安全文本足够长".startsWith(out.toString()));
                        assertEquals(
                                "stream_content_denied",
                                ((GovernanceException) errors.get(0)).code());
                        gate.accept("late");
                        assertEquals(0, gate.bufferedCharacters());
                        assertFalse(gate.hasScheduledCheck());
                    }
                }
            }
        }
    }

    @Test
    void safeStreamIsIncrementalAndSurrogatePairsStayTogether() {
        try (ChunkBufferEngineTest.Clock clock = new Clock()) {
            StringBuilder out = new StringBuilder();
            ArrayList<Throwable> errors = new ArrayList<Throwable>();
            ArrayList<String> parts = new ArrayList<String>();
            try (ChunkBufferEngine gate =
                    new ChunkBufferEngine(
                            ChunkBufferOptions.defaults(Java8.set("local")),
                            Java8.list(rule(Java8.list("ABCDEF"))),
                            clock,
                            true,
                            (r, a, b, n) -> {},
                            parts::add,
                            errors::add)) {
                String text = "你好，abcdefghijk😀结束";
                for (int i = 0; i < text.length(); i++) {
                    gate.accept(text.substring(i, i + 1));
                    clock.tick();
                }
                assertFalse(parts.isEmpty());
                assertTrue(
                        parts.stream()
                                .noneMatch(
                                        p -> Character.isHighSurrogate(p.charAt(p.length() - 1))));
                gate.seal(text);
                gate.complete(text);
                parts.forEach(out::append);
                assertEquals(text, out.toString());
                assertTrue(errors.isEmpty());
                assertEquals(0, gate.bufferedCharacters());
            }
        }
    }

    @Test
    void timeoutChecksOnceAndNeverReleasesPendingPrefix() {
        try (ChunkBufferEngineTest.Clock clock = new Clock()) {
            StringBuilder out = new StringBuilder();
            ArrayList<Throwable> errors = new ArrayList<Throwable>();
            try (ChunkBufferEngine gate =
                    engine(clock, Java8.list("ABCDEF"), true, out, errors, new ArrayList<>())) {
                gate.accept("ABC");
                clock.tick();
                clock.tick();
                assertEquals("", out.toString());
                assertFalse(gate.hasScheduledCheck());
                gate.close();
                gate.accept("DEF");
                clock.tick();
                assertEquals("", out.toString());
                assertEquals(0, gate.bufferedCharacters());
            }
        }
    }

    @Test
    void evaluateOnlyRecordsEachOccurrenceOnceAcrossOverlapRescans() {
        try (ChunkBufferEngineTest.Clock clock = new Clock()) {
            StringBuilder out = new StringBuilder();
            ArrayList<Throwable> errors = new ArrayList<Throwable>();
            ArrayList<String> audits = new ArrayList<String>();
            try (ChunkBufferEngine gate =
                    engine(clock, Java8.list("ABCDEF", "BC"), false, out, errors, audits)) {
                String text = "ABCDEFabcdefghijkl";
                for (char c : text.toCharArray()) {
                    gate.accept(String.valueOf(c));
                    clock.tick();
                }
                gate.seal(text);
                gate.complete(text);
                assertEquals(text, out.toString());
                assertEquals(2, audits.size());
                assertTrue(errors.isEmpty());
            }
        }
    }

    @Test
    void shortResponseMismatchAuditFailureAndCapacityFailClosed() {
        try (ChunkBufferEngineTest.Clock clock = new Clock()) {
            StringBuilder out = new StringBuilder();
            ArrayList<Throwable> errors = new ArrayList<Throwable>();
            try (ChunkBufferEngine gate =
                    engine(clock, Java8.list("ABCDEF"), true, out, errors, new ArrayList<>())) {
                gate.accept("hi");
                gate.seal("hi");
                gate.complete("hi");
                assertEquals("hi", out.toString());
            }
            out.setLength(0);
            try (ChunkBufferEngine gate =
                    engine(clock, Java8.list("ABCDEF"), true, out, errors, new ArrayList<>())) {
                gate.accept("ABC");
                assertThrows(GovernanceException.class, () -> gate.seal("changed"));
                assertEquals("", out.toString());
                assertEquals(0, gate.bufferedCharacters());
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ChunkBufferEngine(
                                    new ChunkBufferOptions(
                                            3, "", Duration.ofMillis(1), 4, Java8.set("local")),
                                    Java8.list(rule(Java8.list("ABCDEF"))),
                                    clock,
                                    true,
                                    (r, a, b, n) -> {},
                                    out::append,
                                    errors::add));
            try (ChunkBufferEngine gate =
                    new ChunkBufferEngine(
                            ChunkBufferOptions.defaults(Java8.set("local")),
                            Java8.list(rule(Java8.list("X"))),
                            clock,
                            true,
                            (r, a, b, n) -> {
                                throw new GovernanceException("audit_failed");
                            },
                            out::append,
                            errors::add)) {
                gate.accept("safe。");
                assertEquals("", out.toString());
                assertEquals(
                        "audit_failed",
                        ((GovernanceException) errors.get(errors.size() - 1)).code());
            }
        }
    }
}
