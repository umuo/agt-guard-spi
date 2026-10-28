package org.agentguard.benchmarks;

import io.agt.core.InterventionPoint;
import io.agt.core.Java8;
import io.agt.core.policy.ContentRule;
import io.agt.core.streaming.ChunkBufferEngine;
import io.agt.core.streaming.ChunkBufferOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对窗口内核进行一次受控并发观测，复用现有 Audit 和缓冲长度入口。
 * 输出首段、检查耗时、缓冲高水位及调度器数量，不替代 JMH 的统计测量。
 */
public final class StreamingProbe {
    private StreamingProbe() {}

    /**
     * 汇总实际创建的定时器线程，线程退出后减少存活计数。
     */
    private static final class Timers {
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();
        private final AtomicInteger scheduled = new AtomicInteger();
        private final List<Thread> threads = Collections.synchronizedList(new ArrayList<Thread>());
    }

    /**
     * 只包装真实调度器计数，不改变调度时间与取消行为。
     */
    private static final class Scheduler extends ScheduledThreadPoolExecutor {
        Scheduler(Timers timers) {
            super(
                    1,
                    task -> {
                        Thread thread =
                                new Thread(
                                        () -> {
                                            int active = timers.active.incrementAndGet();
                                            timers.peak.accumulateAndGet(active, Math::max);
                                            try {
                                                task.run();
                                            } finally {
                                                timers.active.decrementAndGet();
                                            }
                                        },
                                        "guard-probe-timer");
                        thread.setDaemon(true);
                        timers.threads.add(thread);
                        return thread;
                    });
            setRemoveOnCancelPolicy(true);
            this.timers = timers;
        }

        private final Timers timers;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            timers.scheduled.incrementAndGet();
            return super.schedule(task, delay, unit);
        }
    }

    /**
     * 单个会话的观测结果，检查和输出回调由窗口锁串行保护。
     */
    private static final class Sample {
        private long started;
        private long firstRelease = -1;
        private long checkedNanos;
        private long checks;
        private int peakBuffered;
        private int emitted;
    }

    public static void main(String[] args) throws Exception {
        int characters = args.length > 0 ? Integer.parseInt(args[0]) : 65536;
        int callback = args.length > 1 ? Integer.parseInt(args[1]) : 1024;
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        if (characters < 1
                || characters > 1048576
                || callback < 1
                || callback > 1048576
                || concurrency < 1
                || concurrency > 64) {
            throw new IllegalArgumentException(
                    "Expected characters/callback in 1..1048576 and concurrency in 1..64");
        }
        String text = Java8.repeat("safe ", characters / 5 + 1).substring(0, characters);
        Timers timers = new Timers();
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        List<Future<Sample>> results = new ArrayList<>();
        try {
            for (int i = 0; i < concurrency; i++) {
                results.add(
                        workers.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException(
                                                "Probe start barrier timed out");
                                    }
                                    return measure(text, callback, timers);
                                }));
            }
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Probe workers did not start");
            }
            start.countDown();
            long first = 0;
            long checkNanos = 0;
            long checks = 0;
            int peakBuffered = 0;
            for (Future<Sample> result : results) {
                Sample sample = result.get(30, TimeUnit.SECONDS);
                if (sample.emitted != characters || sample.firstRelease < 0) {
                    throw new IllegalStateException(
                            "Probe did not release the complete safe response");
                }
                first += sample.firstRelease;
                checkNanos += sample.checkedNanos;
                checks += sample.checks;
                peakBuffered = Math.max(peakBuffered, sample.peakBuffered);
            }
            for (Thread thread : timers.threads) {
                thread.join(5000);
                if (thread.isAlive()) {
                    throw new IllegalStateException("Probe timer thread did not exit");
                }
            }
            if (timers.active.get() != 0) {
                throw new IllegalStateException("Probe timer thread leaked");
            }
            System.out.println(
                    "characters,callback,concurrency,meanFirstReleaseUs,meanCheckUs,maxBufferedUtf16,scheduledTasks,peakTimerThreads,remainingTimerThreads");
            System.out.printf(
                    Locale.ROOT,
                    "%d,%d,%d,%.3f,%.3f,%d,%d,%d,%d%n",
                    characters,
                    callback,
                    concurrency,
                    first / (concurrency * 1000.0),
                    checkNanos / (checks * 1000.0),
                    peakBuffered,
                    timers.scheduled.get(),
                    timers.peak.get(),
                    timers.active.get());
        } finally {
            start.countDown();
            workers.shutdownNow();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Probe workers did not terminate");
            }
        }
    }

    private static Sample measure(String text, int callback, Timers timers) throws Exception {
        Sample sample = new Sample();
        Scheduler scheduler = new Scheduler(timers);
        AtomicReference<ChunkBufferEngine> current = new AtomicReference<>();
        ContentRule rule =
                new ContentRule(
                        "local",
                        Collections.singleton(InterventionPoint.POST_MODEL_CALL),
                        Collections.singletonList("SECRET"),
                        Collections.emptyMap());
        try (ChunkBufferEngine stream =
                new ChunkBufferEngine(
                        new ChunkBufferOptions(
                                64,
                                "",
                                Duration.ofMillis(50),
                                4096,
                                Collections.singleton("local")),
                        Collections.singletonList(rule),
                        scheduler,
                        true,
                        (id, offset, end, nanos) -> {
                            sample.checkedNanos += nanos;
                            sample.checks++;
                            sample.peakBuffered =
                                    Math.max(
                                            sample.peakBuffered,
                                            current.get().bufferedCharacters());
                        },
                        part -> {
                            if (sample.firstRelease < 0) {
                                sample.firstRelease = System.nanoTime() - sample.started;
                            }
                            sample.emitted += part.length();
                        },
                        error -> {
                            throw new IllegalStateException("Unexpected probe failure", error);
                        },
                        Runnable::run,
                        scheduler::shutdownNow)) {
            current.set(stream);
            sample.started = System.nanoTime();
            for (int offset = 0; offset < text.length(); offset += callback) {
                stream.accept(text.substring(offset, Math.min(text.length(), offset + callback)));
            }
            stream.seal(text);
            stream.complete(text);
        } finally {
            scheduler.shutdownNow();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Probe scheduler did not terminate");
            }
        }
        return sample;
    }
}
