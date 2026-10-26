package org.agentguard.agt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.agt.core.InterventionPoint;
import io.agt.core.Java8;
import io.agt.core.JsonValues;
import io.agt.core.PolicyDecision;
import io.agt.core.PolicyEvaluator;
import io.agt.core.PolicyRequest;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.context.StandardAttributes;
import org.agentguard.core.GuardEngineBuilder;
import org.agentguard.engine.GuardEngine;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.model.AgentInput;
import org.agentguard.model.AgentOutput;
import org.agentguard.model.ContentPart;
import org.agentguard.model.GuardMessage;
import org.agentguard.model.ModelRequest;
import org.agentguard.model.ModelResponse;
import org.agentguard.model.Role;
import org.agentguard.model.TokenUsage;
import org.agentguard.model.ToolCall;
import org.agentguard.model.ToolDefinition;
import org.agentguard.model.ToolResult;
import org.agentguard.spi.GuardEngineFactory;
import org.junit.Test;

/**
 * 验证六阶段策略、可信授权、改写恢复、故障关闭和真实 SPI 描述文件装配。
 */
public class AgtGuardProviderTest {
    private static final String VALID_ARGUMENTS = "{\"tenant\":\"tenant-a\",\"query\":\"SECRET\"}";

    private static GuardContext context() {
        return GuardContext.builder()
                .agentId("assistant")
                .tenantId("tenant-a")
                .userId("user-a")
                .attributes(Java8.map(StandardAttributes.PERMISSIONS, Java8.set("read")))
                .build();
    }

    private static StructuredPolicy policy(boolean approval, List<ContentRule> rules) {
        StructuredPolicy.Builder builder =
                StructuredPolicy.builder("v1")
                        .tool(
                                "lookup",
                                new ToolRule(
                                        Java8.set("read"),
                                        ParameterRule.object(
                                                Java8.map(
                                                        "tenant",
                                                        ParameterRule.string("tenantId"),
                                                        "query",
                                                        ParameterRule.string(null)),
                                                "tenant",
                                                "query"),
                                        approval));
        for (ContentRule rule : rules) {
            builder.content(rule);
        }
        return builder.build();
    }

    private static ContentRule rule(
            InterventionPoint point, List<String> deny, Map<String, String> rewrite) {
        return new ContentRule("rule", Java8.set(point), deny, rewrite);
    }

    private static GuardEngine engine(PolicyEvaluator evaluator) {
        return new GuardEngineBuilder().addProvider(new AgtGuardProvider(evaluator)).build();
    }

    private static Object payload(GuardStage stage, String text) {
        switch (stage) {
            case AGENT_INPUT:
                return AgentInput.of(text);
            case MODEL_INPUT:
                return ModelRequest.builder()
                        .messages(
                                Java8.list(GuardMessage.system("system"), GuardMessage.user(text)))
                        .newMessagesFrom(1)
                        .build();
            case MODEL_OUTPUT:
                return ModelResponse.of(GuardMessage.assistant(text));
            case TOOL_INPUT:
                return ToolCall.of(
                        "call",
                        "lookup",
                        JsonValues.write(Java8.map("tenant", "tenant-a", "query", text)));
            case TOOL_OUTPUT:
                return ToolResult.of("call", "lookup", text);
            case AGENT_OUTPUT:
                return AgentOutput.of(text);
            default:
                throw new IllegalStateException("Unknown enum value");
        }
    }

    private static String text(GuardStage stage, Object payload) {
        switch (stage) {
            case AGENT_INPUT:
                return ((AgentInput) payload).text();
            case MODEL_INPUT:
                return ((ModelRequest) payload).messages().get(1).text();
            case MODEL_OUTPUT:
                return ((ModelResponse) payload).text();
            case TOOL_INPUT:
                return (String)
                        JsonValues.parseObject(((ToolCall) payload).arguments()).get("query");
            case TOOL_OUTPUT:
                return ((ToolResult) payload).content();
            case AGENT_OUTPUT:
                return ((AgentOutput) payload).text();
            default:
                throw new IllegalStateException("Unknown enum value");
        }
    }

    @Test
    public void allSixStagesAllowDenyAndRewrite() {
        for (GuardStage stage : GuardStage.values()) {
            GuardEngine engine =
                    engine(
                            policy(
                                    false,
                                    Java8.list(
                                            rule(
                                                    AgtGuardProvider.point(stage),
                                                    Java8.list("DENIED"),
                                                    Java8.map("SECRET", "MASKED")))));
            Object original = payload(stage, "hello");
            assertSame(original, engine.check(stage, original, context()).payloadOrThrow());
            assertThrows(
                    stage.name(),
                    GuardBlockedException.class,
                    () ->
                            engine.check(stage, payload(stage, "DENIED"), context())
                                    .payloadOrThrow());
            Object rewritten =
                    engine.check(stage, payload(stage, "SECRET"), context()).payloadOrThrow();
            assertTrue(stage.payloadType().isInstance(rewritten));
            assertEquals("MASKED", text(stage, rewritten));
        }
    }

    @Test
    public void scansHistoryEvenWhenNewMessagesStartLater() {
        GuardEngine engine =
                engine(
                        policy(
                                false,
                                Java8.list(
                                        rule(
                                                InterventionPoint.PRE_MODEL_CALL,
                                                Java8.list("DENIED"),
                                                Java8.map()))));
        ModelRequest request =
                ModelRequest.builder()
                        .messages(
                                Java8.list(
                                        GuardMessage.system("DENIED"), GuardMessage.user("hello")))
                        .newMessagesFrom(1)
                        .build();
        assertThrows(
                GuardBlockedException.class,
                () -> engine.checkModelInput(request, context()).payloadOrThrow());
    }

    @Test
    public void scansModelToolCallsAndToolDefinitions() {
        GuardMessage message =
                GuardMessage.builder()
                        .role(Role.ASSISTANT)
                        .toolCalls(Java8.list(ToolCall.of("call", "lookup", VALID_ARGUMENTS)))
                        .build();
        GuardEngine output =
                engine(
                        policy(
                                false,
                                Java8.list(
                                        rule(
                                                InterventionPoint.POST_MODEL_CALL,
                                                Java8.list("SECRET"),
                                                Java8.map()))));
        assertThrows(
                GuardBlockedException.class,
                () ->
                        output.checkModelOutput(ModelResponse.of(message), context())
                                .payloadOrThrow());
        GuardEngine input =
                engine(
                        policy(
                                false,
                                Java8.list(
                                        rule(
                                                InterventionPoint.PRE_MODEL_CALL,
                                                Java8.list("SECRET"),
                                                Java8.map()))));
        ModelRequest request =
                ModelRequest.builder()
                        .tools(
                                Java8.list(
                                        ToolDefinition.of(
                                                "lookup", "SECRET", "{\"type\":\"object\"}")))
                        .build();
        assertThrows(
                GuardBlockedException.class,
                () -> input.checkModelInput(request, context()).payloadOrThrow());
    }

    @Test
    public void rejectsTenantPermissionUnknownToolAndUnknownParameters() {
        GuardEngine engine = engine(policy(false, Java8.list()));
        for (ToolCall call :
                Java8.list(
                        ToolCall.of("call", "lookup", "{\"tenant\":\"tenant-b\",\"query\":\"ok\"}"),
                        ToolCall.of("call", "unknown", VALID_ARGUMENTS),
                        ToolCall.of(
                                "call",
                                "lookup",
                                "{\"tenant\":\"tenant-a\",\"query\":\"ok\",\"extra\":1}"))) {
            assertThrows(
                    GuardBlockedException.class,
                    () -> engine.checkToolInput(call, context()).payloadOrThrow());
        }
        assertThrows(
                GuardBlockedException.class,
                () ->
                        engine.checkToolInput(
                                        ToolCall.of("call", "lookup", VALID_ARGUMENTS),
                                        context().toBuilder().attributes(Java8.map()).build())
                                .payloadOrThrow());
    }

    @Test
    public void revalidatesTransformedTenantAndApproval() {
        GuardEngine engine =
                engine(
                        policy(
                                false,
                                Java8.list(
                                        rule(
                                                InterventionPoint.PRE_TOOL_CALL,
                                                Java8.list(),
                                                Java8.map("tenant-a", "tenant-b")))));
        assertThrows(
                GuardBlockedException.class,
                () ->
                        engine.checkToolInput(
                                        ToolCall.of("call", "lookup", VALID_ARGUMENTS), context())
                                .payloadOrThrow());
        GuardEngine approval =
                engine(
                        policy(
                                true,
                                Java8.list(
                                        rule(
                                                InterventionPoint.PRE_TOOL_CALL,
                                                Java8.list(),
                                                Java8.map("SECRET", "MASKED")))));
        assertEquals(
                "approval_unsupported",
                assertThrows(
                                GuardBlockedException.class,
                                () ->
                                        approval.checkToolInput(
                                                        ToolCall.of(
                                                                "call", "lookup", VALID_ARGUMENTS),
                                                        context())
                                                .payloadOrThrow())
                        .findings()
                        .get(0)
                        .code());
    }

    @Test
    public void rejectsApprovalWithoutRewrite() {
        GuardEngine engine = engine(policy(true, Java8.list()));
        assertThrows(
                GuardBlockedException.class,
                () ->
                        engine.checkToolInput(
                                        ToolCall.of("call", "lookup", VALID_ARGUMENTS), context())
                                .payloadOrThrow());
    }

    @Test
    public void missingIdentityMalformedPermissionAndInvalidJsonFailClosed() {
        GuardEngine engine = engine(policy(false, Java8.list()));
        assertThrows(
                GuardExecutionException.class,
                () ->
                        engine.checkAgentInput(
                                        AgentInput.of("hello"), GuardContext.builder().build())
                                .payloadOrThrow());
        assertThrows(
                GuardExecutionException.class,
                () ->
                        engine.checkAgentInput(
                                        AgentInput.of("hello"),
                                        context().toBuilder()
                                                .attributes(
                                                        Java8.map(
                                                                StandardAttributes.PERMISSIONS,
                                                                Java8.list("read")))
                                                .build())
                                .payloadOrThrow());
        for (String arguments : Java8.list("[]", "{", "{\"query\":1,\"query\":2}")) {
            assertThrows(
                    GuardExecutionException.class,
                    () ->
                            engine.checkToolInput(
                                            ToolCall.of("call", "lookup", arguments), context())
                                    .payloadOrThrow());
        }
    }

    @Test
    public void rejectsMediaAndPojoAndChecksStructuredJson() {
        GuardEngine engine =
                engine(
                        policy(
                                false,
                                Java8.list(
                                        rule(
                                                InterventionPoint.OUTPUT,
                                                Java8.list("DENIED"),
                                                Java8.map()))));
        GuardMessage media =
                GuardMessage.builder()
                        .role(Role.USER)
                        .addContent(ContentPart.media("image/png", new byte[] {1}))
                        .build();
        assertThrows(
                GuardExecutionException.class,
                () -> engine.checkAgentInput(AgentInput.of(media), context()).payloadOrThrow());
        assertThrows(
                GuardExecutionException.class,
                () ->
                        engine.checkAgentOutput(
                                        AgentOutput.builder().structured(new Object()).build(),
                                        context())
                                .payloadOrThrow());
        assertThrows(
                GuardBlockedException.class,
                () ->
                        engine.checkAgentOutput(
                                        AgentOutput.builder()
                                                .structured(Java8.map("hidden", "DENIED"))
                                                .build(),
                                        context())
                                .payloadOrThrow());
    }

    @Test
    public void structuralRewritesCannotChangeToolIdentity() {
        GuardEngine engine =
                engine(
                        policy(
                                false,
                                Java8.list(
                                        rule(
                                                InterventionPoint.POST_MODEL_CALL,
                                                Java8.list(),
                                                Java8.map("lookup", "different")))));
        GuardMessage message =
                GuardMessage.builder()
                        .role(Role.ASSISTANT)
                        .toolCalls(Java8.list(ToolCall.of("call", "lookup", VALID_ARGUMENTS)))
                        .build();
        assertThrows(
                GuardExecutionException.class,
                () ->
                        engine.checkModelOutput(ModelResponse.of(message), context())
                                .payloadOrThrow());
    }

    @Test
    public void preservesMetadataUsageAndErrorOnRewrite() {
        GuardEngine engine =
                engine(
                        policy(
                                false,
                                Java8.list(
                                        new ContentRule(
                                                "rewrite",
                                                Java8.set(
                                                        InterventionPoint.POST_MODEL_CALL,
                                                        InterventionPoint.POST_TOOL_CALL),
                                                Java8.list(),
                                                Java8.map("SECRET", "MASKED")))));
        TokenUsage usage = TokenUsage.of(10, 2);
        ModelResponse response =
                ModelResponse.builder()
                        .message(GuardMessage.assistant("SECRET"))
                        .usage(usage)
                        .metadata(Java8.map("vendor", "metadata"))
                        .build();
        ModelResponse changed = engine.checkModelOutput(response, context()).payloadOrThrow();
        assertSame(usage, changed.usage());
        assertEquals(response.metadata(), changed.metadata());
        ToolResult error = ToolResult.error("call", "lookup", "SECRET");
        ToolResult rewritten = engine.checkToolOutput(error, context()).payloadOrThrow();
        assertTrue(rewritten.isError());
        assertEquals("call", rewritten.callId());
        assertEquals("MASKED", rewritten.content());
    }

    @Test
    public void policyFailuresRemainExecutionErrors() {
        PolicyEvaluator failure =
                new PolicyEvaluator() {
                    @Override
                    public String version() {
                        return "failure";
                    }

                    @Override
                    public Set<InterventionPoint> interventionPoints() {
                        return InterventionPoint.lifecyclePoints();
                    }

                    @Override
                    public PolicyDecision evaluate(PolicyRequest request) {
                        throw new IllegalStateException("offline");
                    }
                };
        GuardExecutionException exception =
                assertThrows(
                        GuardExecutionException.class,
                        () ->
                                engine(failure)
                                        .checkAgentInput(AgentInput.of("hello"), context())
                                        .payloadOrThrow());
        assertTrue(exception.getCause() instanceof IllegalStateException);
    }

    @Test
    public void refusesMissingInterventionPoints() {
        StructuredPolicy incomplete =
                StructuredPolicy.builder("incomplete")
                        .points(Java8.set(InterventionPoint.INPUT))
                        .build();
        assertThrows(IllegalArgumentException.class, () -> new AgtGuardProvider(incomplete));
    }

    @Test
    public void requestStateIsIsolatedAndThreadSafe() throws Exception {
        GuardEngine engine = engine(policy(false, Java8.list()));
        GuardContext shared = context();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                calls.add(
                        () ->
                                engine.checkAgentInput(AgentInput.of("hello"), shared)
                                        .payloadOrThrow());
            }
            for (Future<Object> future : executor.invokeAll(calls)) {
                assertNotNull(future.get());
            }
            GuardContext other = context();
            engine.checkAgentInput(AgentInput.of("hello"), other).payloadOrThrow();
            assertEquals(1, shared.state().size());
            assertFalse(shared.state().equals(other.state()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void serviceLoaderLoadsRealDescriptorsAndYaml() throws Exception {
        Path path = Files.createTempFile("agt-policy-", ".yaml");
        String previous = System.getProperty(AgtGuardProvider.POLICY_PATH_PROPERTY);
        try {
            Java8.writeString(path, Java8.readString(Paths.get("src/test/resources/policy.yaml")));
            System.setProperty(AgtGuardProvider.POLICY_PATH_PROPERTY, path.toString());
            List<GuardEngineFactory> factories = new ArrayList<>();
            ServiceLoader.load(GuardEngineFactory.class).forEach(factories::add);
            assertEquals(1, factories.size());
            GuardEngine engine = factories.get(0).create();
            assertThrows(
                    GuardBlockedException.class,
                    () ->
                            engine.checkAgentInput(AgentInput.of("DENIED"), context())
                                    .payloadOrThrow());
            assertEquals(
                    "MASKED",
                    engine.checkAgentOutput(AgentOutput.of("SECRET"), context())
                            .payloadOrThrow()
                            .text());
        } finally {
            restoreProperty(previous);
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void serviceLoaderFailsForMissingAndInvalidConfiguration() throws Exception {
        Path invalid = Files.createTempFile("agt-invalid-", ".yaml");
        String previous = System.getProperty(AgtGuardProvider.POLICY_PATH_PROPERTY);
        try {
            System.setProperty(AgtGuardProvider.POLICY_PATH_PROPERTY, "");
            assertThrows(
                    ServiceConfigurationError.class, () -> new AgtGuardEngineFactory().create());
            Java8.writeString(invalid, "unknown: invalid");
            System.setProperty(AgtGuardProvider.POLICY_PATH_PROPERTY, invalid.toString());
            assertThrows(
                    ServiceConfigurationError.class, () -> new AgtGuardEngineFactory().create());
        } finally {
            restoreProperty(previous);
            Files.deleteIfExists(invalid);
        }
    }

    private static void restoreProperty(String previous) {
        if (previous == null) {
            System.clearProperty(AgtGuardProvider.POLICY_PATH_PROPERTY);
        } else {
            System.setProperty(AgtGuardProvider.POLICY_PATH_PROPERTY, previous);
        }
    }
}
