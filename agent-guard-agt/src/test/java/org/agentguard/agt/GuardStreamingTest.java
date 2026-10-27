package org.agentguard.agt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.core.GuardEngineBuilder;
import org.agentguard.decision.GuardDecision;
import org.agentguard.decision.GuardPolicy;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.model.ModelResponse;
import org.agentguard.spi.ModelOutputGuard;
import org.agentguard.streaming.GuardStream;
import org.agentguard.streaming.GuardStreamOptions;
import org.agentguard.streaming.StreamingGuardEngine;
import org.junit.Test;

/**
 * 验证中立流会话的尾部保留、完整缓存、兼容性拒绝及关闭语义。
 * 使用真实 AGT 字面匹配缓冲器，不模拟窗口算法。
 */
public class GuardStreamingTest {
    private static GuardContext context() {
        return GuardContext.builder().agentId("demo").tenantId("tenant-a").userId("user-a").build();
    }

    private static StreamingGuardEngine engine() {
        return (StreamingGuardEngine) AgtGuards.builder().denyText("SECRET").build();
    }

    @Test
    public void windowRetainsPotentialMatchAndBlocksAcrossChunks() {
        StringBuilder output = new StringBuilder();
        List<Throwable> errors = new ArrayList<>();
        try (GuardStream stream =
                engine().openStream(
                                GuardStreamOptions.window(1, 50, 64),
                                context(),
                                output::append,
                                errors::add)) {
            stream.accept("helloSEC");
            assertEquals("hel", output.toString());
            assertThrows(GuardBlockedException.class, () -> stream.accept("RET"));
            assertEquals(1, errors.size());
            assertEquals("hello", output.toString());
            stream.accept("late data");
            assertEquals("hello", output.toString());
        }
    }

    @Test
    public void windowSealsAndReleasesSafeTailOnComplete() {
        StringBuilder output = new StringBuilder();
        try (GuardStream stream =
                engine().openStream(
                                GuardStreamOptions.window(1, 50, 64),
                                context(),
                                output::append,
                                error -> {})) {
            stream.accept("hello");
            assertEquals("", output.toString());
            stream.seal("hello");
            assertEquals("", output.toString());
            stream.complete("hello");
            assertEquals("hello", output.toString());
        }
    }

    @Test
    public void detectsChangedAuthoritativeBodyAndUnexpectedRewrites() {
        try (GuardStream stream =
                engine().openStream(
                                GuardStreamOptions.window(1, 50, 64),
                                context(),
                                text -> {},
                                error -> {})) {
            stream.accept("hello");
            assertThrows(GuardExecutionException.class, () -> stream.seal("different"));
        }
        try (GuardStream stream =
                engine().openStream(
                                GuardStreamOptions.window(1, 50, 64),
                                context(),
                                text -> {},
                                error -> {})) {
            stream.accept("hello");
            stream.seal("hello");
            assertThrows(GuardExecutionException.class, () -> stream.complete("rewritten"));
        }
    }

    @Test
    public void strictBuffersUntilCheckedReplacementAndLimitsMemory() {
        StreamingGuardEngine engine = engine();
        StringBuilder output = new StringBuilder();
        try (GuardStream stream =
                engine.openStream(
                        GuardStreamOptions.strict(16), context(), output::append, error -> {})) {
            stream.accept("PRIVATE");
            assertEquals("", output.toString());
            stream.seal("PRIVATE");
            stream.complete("MASKED");
            stream.accept("late");
            assertEquals("MASKED", output.toString());
        }
        try (GuardStream stream =
                engine.openStream(
                        GuardStreamOptions.strict(4), context(), output::append, error -> {})) {
            assertThrows(GuardExecutionException.class, () -> stream.accept("12345"));
        }
    }

    @Test
    public void strictRejectsMismatchedChunksAndRequiresSeal() {
        try (GuardStream stream =
                engine().openStream(
                                GuardStreamOptions.strict(), context(), text -> {}, error -> {})) {
            stream.accept("a");
            assertThrows(GuardExecutionException.class, () -> stream.seal("b"));
        }
        try (GuardStream stream =
                engine().openStream(
                                GuardStreamOptions.strict(), context(), text -> {}, error -> {})) {
            assertThrows(GuardExecutionException.class, () -> stream.complete("a"));
        }
    }

    @Test
    public void validatesWindowRuleAndBufferCompatibility() {
        assertThrows(
                IllegalArgumentException.class,
                () -> engine().validateStreaming(GuardStreamOptions.window(60, 50, 64)));
        StreamingGuardEngine rewrite =
                (StreamingGuardEngine)
                        AgtGuards.builder()
                                .denyText("SECRET")
                                .maskAt(GuardStage.AGENT_OUTPUT, "x", "y")
                                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> rewrite.validateStreaming(GuardStreamOptions.window()));
        rewrite.validateStreaming(GuardStreamOptions.strict());
        StreamingGuardEngine empty =
                (StreamingGuardEngine) AgtGuards.builder().maskInput("x", "y").build();
        assertThrows(
                IllegalArgumentException.class,
                () -> empty.validateStreaming(GuardStreamOptions.window()));
    }

    @Test
    public void refusesAdditionalOutputGuardsAndDynamicPoliciesInWindowMode() {
        GuardEngineBuilder builder =
                new GuardEngineBuilder()
                        .addProvider(AgtGuards.builder().denyText("SECRET").buildProvider());
        builder.addGuard(
                new ModelOutputGuard() {
                    @Override
                    public String id() {
                        return "extra";
                    }

                    @Override
                    public GuardDecision<ModelResponse> check(
                            ModelResponse payload, GuardContext context) {
                        return GuardDecision.pass();
                    }
                });
        StreamingGuardEngine composed = (StreamingGuardEngine) builder.build();
        assertThrows(
                IllegalArgumentException.class,
                () -> composed.validateStreaming(GuardStreamOptions.window()));
        composed.validateStreaming(GuardStreamOptions.strict());
        StreamingGuardEngine dynamic =
                (StreamingGuardEngine)
                        new GuardEngineBuilder()
                                .addProvider(AgtGuards.builder().denyText("SECRET").buildProvider())
                                .policyResolver((guard, context) -> GuardPolicy.DEFAULT)
                                .build();
        assertThrows(
                IllegalArgumentException.class,
                () -> dynamic.validateStreaming(GuardStreamOptions.window()));
    }

    @Test
    public void includesFinalOutputDenyRulesAndPreservesUnicode() {
        StreamingGuardEngine engine =
                (StreamingGuardEngine)
                        AgtGuards.builder().denyTextAt(GuardStage.AGENT_OUTPUT, "SECRET").build();
        StringBuilder output = new StringBuilder();
        try (GuardStream stream =
                engine.openStream(
                        GuardStreamOptions.window(1, 50, 64),
                        context(),
                        output::append,
                        error -> {})) {
            stream.accept("\uD83D");
            stream.accept("\uDE00ok");
            stream.seal("\uD83D\uDE00ok");
            stream.complete("\uD83D\uDE00ok");
            assertEquals("\uD83D\uDE00ok", output.toString());
        }
    }
}
