package org.agentguard.benchmarks;

import io.agt.core.InterventionPoint;
import io.agt.core.Java8;
import io.agt.core.policy.ContentRule;
import io.agt.core.streaming.ChunkBufferEngine;
import io.agt.core.streaming.ChunkBufferOptions;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.agentguard.agt.AgtGuards;
import org.agentguard.context.GuardContext;
import org.agentguard.model.AgentOutput;
import org.agentguard.model.GuardMessage;
import org.agentguard.model.ModelResponse;
import org.agentguard.streaming.GuardStream;
import org.agentguard.streaming.GuardStreamOptions;
import org.agentguard.streaming.StreamingGuardEngine;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * 测量窗口内核与 SDK 完整生命周期，生产依赖不包含此可选模块。
 * 每个 JMH 工作线程独立持有调度器，支持使用线程数参数观察并发成本。
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class StreamingBenchmark {
    @Param({"4096", "65536"})
    public int characters;

    @Param({"16", "1024"})
    public int callbackCharacters;

    private String text;
    private List<String> chunks;
    private List<ContentRule> rules;
    private ScheduledThreadPoolExecutor scheduler;
    private StreamingGuardEngine engine;
    private GuardContext context;
    private long emitted;

    @Setup(Level.Trial)
    public void setup() {
        text = Java8.repeat("safe ", characters / 5 + 1).substring(0, characters);
        chunks = new java.util.ArrayList<>();
        for (int offset = 0; offset < text.length(); offset += callbackCharacters) {
            chunks.add(
                    text.substring(offset, Math.min(text.length(), offset + callbackCharacters)));
        }
        rules =
                Collections.singletonList(
                        new ContentRule(
                                "local",
                                Collections.singleton(InterventionPoint.POST_MODEL_CALL),
                                Collections.singletonList("SECRET"),
                                Collections.emptyMap()));
        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        engine = (StreamingGuardEngine) AgtGuards.builder().denyText("SECRET").build();
        context =
                GuardContext.builder()
                        .agentId("benchmark")
                        .tenantId("tenant")
                        .userId("user")
                        .build();
    }

    @TearDown(Level.Trial)
    public void shutdown() throws InterruptedException {
        scheduler.shutdownNow();
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Benchmark scheduler did not stop");
        }
    }

    @Benchmark
    public long windowKernel() {
        emitted = 0;
        try (ChunkBufferEngine stream =
                new ChunkBufferEngine(
                        new ChunkBufferOptions(
                                64,
                                "",
                                Duration.ofMillis(50),
                                4096,
                                Collections.singleton("local")),
                        rules,
                        scheduler,
                        true,
                        (rule, start, end, nanos) -> {},
                        part -> emitted += part.length(),
                        error -> {
                            throw new IllegalStateException(
                                    "Unexpected benchmark rejection", error);
                        })) {
            chunks.forEach(stream::accept);
            stream.seal(text);
            stream.complete(text);
        }
        return emitted;
    }

    @Benchmark
    public long strictSdk() {
        return sdk(GuardStreamOptions.strict());
    }

    @Benchmark
    public long windowSdk() {
        return sdk(GuardStreamOptions.window());
    }

    private long sdk(GuardStreamOptions options) {
        emitted = 0;
        try (GuardStream stream =
                engine.openStream(
                        options,
                        context,
                        part -> emitted += part.length(),
                        error -> {
                            throw new IllegalStateException(
                                    "Unexpected benchmark rejection", error);
                        })) {
            chunks.forEach(stream::accept);
            stream.seal(text);
            ModelResponse model =
                    engine.checkModelOutput(ModelResponse.of(GuardMessage.assistant(text)), context)
                            .payloadOrThrow();
            AgentOutput output =
                    engine.checkAgentOutput(AgentOutput.of(model.text()), context).payloadOrThrow();
            stream.complete(output.text());
        }
        return emitted;
    }
}
