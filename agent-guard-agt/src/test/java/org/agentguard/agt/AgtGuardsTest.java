package org.agentguard.agt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.agt.core.InterventionPoint;
import io.agt.core.Java8;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.context.StandardAttributes;
import org.agentguard.engine.GuardEngine;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.model.AgentInput;
import org.agentguard.model.AgentOutput;
import org.agentguard.model.GuardMessage;
import org.agentguard.model.ModelRequest;
import org.agentguard.model.ModelResponse;
import org.agentguard.model.ToolCall;
import org.agentguard.model.ToolResult;
import org.junit.Test;

/**
 * 验证 Fluent 配置的六阶段行为、授权、不可变快照及配置冲突检查。
 * 测试通过实际 AGT Provider 与参考引擎执行，不模拟策略评估。
 */
public class AgtGuardsTest {
    private static GuardContext context(boolean permission) {
        return GuardContext.builder()
                .agentId("assistant")
                .tenantId("tenant-a")
                .userId("service:demo")
                .attributes(
                        Java8.map(
                                StandardAttributes.PERMISSIONS,
                                permission ? Java8.set("orders:read") : Collections.emptySet()))
                .build();
    }

    private static AgtGuards.Builder orders() {
        return AgtGuards.builder()
                .tool(
                        "lookup",
                        tool ->
                                tool.requirePermission("orders:read")
                                        .bindTenant("tenant")
                                        .requireString("orderId")
                                        .optionalString("note"));
    }

    @Test
    public void deniesTextAtAllSixStages() {
        GuardEngine engine =
                AgtGuards.builder()
                        .denyText("DENIED")
                        .tool("lookup", tool -> tool.requireString("value"))
                        .build();
        for (GuardStage stage : GuardStage.values()) {
            GuardBlockedException blocked =
                    assertThrows(
                            GuardBlockedException.class,
                            () ->
                                    engine.check(stage, deniedPayload(stage), context(true))
                                            .payloadOrThrow());
            assertEquals(stage, blocked.stage());
            assertTrue(blocked.findings().get(0).code().startsWith("agt.fluent.rule."));
        }
    }

    @Test
    public void appliesInputAndToolResultMasksOnlyAtSelectedStages() {
        GuardEngine engine =
                AgtGuards.builder()
                        .maskInput("PRIVATE", "MASKED")
                        .maskToolOutput("PRIVATE", "TOOL_MASKED")
                        .build();
        assertEquals(
                "MASKED",
                engine.checkAgentInput(AgentInput.of("PRIVATE"), context(true))
                        .payloadOrThrow()
                        .text());
        assertEquals(
                "TOOL_MASKED",
                engine.checkToolOutput(ToolResult.of("c", "lookup", "PRIVATE"), context(true))
                        .payloadOrThrow()
                        .content());
        assertEquals(
                "PRIVATE",
                engine.checkModelOutput(
                                ModelResponse.of(GuardMessage.assistant("PRIVATE")), context(true))
                        .payloadOrThrow()
                        .text());
    }

    @Test
    public void enforcesPermissionsTenantRequiredAndOptionalParameters() {
        GuardEngine engine = orders().build();
        ToolCall valid =
                ToolCall.of("c", "lookup", "{\"tenant\":\"tenant-a\",\"orderId\":\"1001\"}");
        assertEquals(valid, engine.checkToolInput(valid, context(true)).payloadOrThrow());
        blockedTool(engine, valid, false, "permission_denied");
        blockedTool(
                engine,
                valid.withArguments("{\"tenant\":\"tenant-b\",\"orderId\":\"1001\"}"),
                true,
                "context_binding_denied");
        blockedTool(
                engine,
                valid.withArguments("{\"tenant\":\"tenant-a\"}"),
                true,
                "tool_arguments_denied");
        blockedTool(
                engine,
                valid.withArguments("{\"tenant\":\"tenant-a\",\"orderId\":1001}"),
                true,
                "tool_arguments_denied");
        blockedTool(engine, ToolCall.of("c", "unknown", "{}"), true, "tool_not_allowed");
        ToolCall withNote =
                valid.withArguments(
                        "{\"tenant\":\"tenant-a\",\"orderId\":\"1001\",\"note\":\"ok\"}");
        assertEquals(withNote, engine.checkToolInput(withNote, context(true)).payloadOrThrow());
    }

    @Test
    public void revalidatesAuthorizationAfterParameterRewrite() {
        GuardEngine engine = orders().maskAt(GuardStage.TOOL_INPUT, "tenant-a", "tenant-b").build();
        ToolCall call =
                ToolCall.of("c", "lookup", "{\"tenant\":\"tenant-a\",\"orderId\":\"1001\"}");
        blockedTool(engine, call, true, "context_binding_denied");
    }

    @Test
    public void preservesMissingIdentityFailure() {
        GuardEngine engine = AgtGuards.builder().denyText("DENIED").build();
        assertThrows(
                GuardExecutionException.class,
                () ->
                        engine.checkAgentInput(
                                        AgentInput.of("hello"), GuardContext.builder().build())
                                .payloadOrThrow());
    }

    @Test
    public void supportsStageSpecificDenyAndIndependentBuildSnapshots() {
        AgtGuards.Builder builder =
                AgtGuards.builder().denyTextAt(GuardStage.MODEL_OUTPUT, "DENIED");
        GuardEngine first = builder.build();
        builder.denyText("NEW");
        GuardEngine second = builder.build();
        assertTrue(first.checkAgentInput(AgentInput.of("DENIED NEW"), context(true)).isProceed());
        assertThrows(
                GuardBlockedException.class,
                () -> second.checkAgentInput(AgentInput.of("NEW"), context(true)).payloadOrThrow());
        assertEquals(6, builder.buildProvider().guards().size());
    }

    @Test
    public void snapshotsToolCallbackAndSupportsAdvancedParameterRules() {
        AtomicReference<AgtGuards.ToolBuilder> captured = new AtomicReference<>();
        AgtGuards.Builder builder =
                AgtGuards.builder()
                        .version("advanced-v1")
                        .tool(
                                "lookup",
                                tool -> {
                                    captured.set(tool);
                                    tool.parameter("query", ParameterRule.string(null), true);
                                });
        captured.get().requireString("later");
        GuardEngine engine = builder.build();
        ToolCall call = ToolCall.of("c", "lookup", "{\"query\":\"hello\"}");
        assertEquals(call, engine.checkToolInput(call, context(true)).payloadOrThrow());
        assertEquals("advanced-v1", builder.buildPolicy().version());
    }

    @Test
    public void rejectsEmptyAndInvalidConfigurations() {
        assertThrows(IllegalStateException.class, () -> AgtGuards.builder().build());
        assertThrows(IllegalArgumentException.class, () -> AgtGuards.builder().denyText());
        assertThrows(IllegalArgumentException.class, () -> AgtGuards.builder().denyText(""));
        assertThrows(IllegalArgumentException.class, () -> AgtGuards.builder().denyText("x", "x"));
        assertThrows(
                NullPointerException.class, () -> AgtGuards.builder().denyText((String[]) null));
        assertThrows(NullPointerException.class, () -> AgtGuards.builder().maskInput("x", null));
        assertThrows(IllegalArgumentException.class, () -> AgtGuards.builder().maskInput("x", "x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgtGuards.builder().version("invalid version"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgtGuards.builder().tool("bad name", tool -> {}));
        assertThrows(NullPointerException.class, () -> AgtGuards.builder().tool("lookup", null));
    }

    @Test
    public void rejectsDuplicateToolParameterPermissionAndContentIdentifiers() {
        AgtGuards.Builder builder = orders();
        assertThrows(IllegalArgumentException.class, () -> builder.tool("lookup", tool -> {}));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgtGuards.builder()
                                .tool(
                                        "lookup",
                                        tool -> tool.bindTenant("tenant").requireString("tenant")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AgtGuards.builder()
                                .tool("lookup", tool -> tool.requirePermission("read", "read")));
        ContentRule rule =
                new ContentRule(
                        "custom", Java8.set(InterventionPoint.INPUT), Java8.list("x"), Java8.map());
        builder.contentRule(rule);
        assertThrows(IllegalArgumentException.class, () -> builder.contentRule(rule));
    }

    @Test
    public void rejectsOverlappingReplacementConflictsButAllowsDifferentStages() {
        AgtGuards.Builder builder = AgtGuards.builder().maskInput("x", "y");
        assertThrows(IllegalArgumentException.class, () -> builder.maskInput("x", "z"));
        ContentRule conflict =
                new ContentRule(
                        "custom",
                        Java8.set(InterventionPoint.INPUT),
                        Java8.list(),
                        Java8.map("x", "y"));
        assertThrows(IllegalArgumentException.class, () -> builder.contentRule(conflict));
        builder.maskToolOutput("x", "z");
        assertEquals(2, builder.buildPolicy().contentRules().size());
    }

    @Test
    public void validatesAdvancedStageBindingsAndAvoidsGeneratedIdCollisions() {
        ContentRule unsupported =
                new ContentRule(
                        "startup",
                        Java8.set(InterventionPoint.AGENT_STARTUP),
                        Java8.list("x"),
                        Java8.map());
        assertThrows(
                IllegalArgumentException.class, () -> AgtGuards.builder().contentRule(unsupported));
        ContentRule reserved =
                new ContentRule(
                        "agt.fluent.rule.1",
                        Java8.set(InterventionPoint.INPUT),
                        Java8.list("x"),
                        Java8.map());
        AgtGuards.Builder builder = AgtGuards.builder().contentRule(reserved).denyText("y");
        assertEquals(2, builder.buildPolicy().contentRules().size());
        assertFalse(
                builder.buildPolicy()
                        .contentRules()
                        .get(0)
                        .id()
                        .equals(builder.buildPolicy().contentRules().get(1).id()));
    }

    private static void blockedTool(
            GuardEngine engine, ToolCall call, boolean permission, String code) {
        GuardBlockedException blocked =
                assertThrows(
                        GuardBlockedException.class,
                        () -> engine.checkToolInput(call, context(permission)).payloadOrThrow());
        assertEquals(GuardStage.TOOL_INPUT, blocked.stage());
        assertEquals(code, blocked.findings().get(0).code());
    }

    private static Object deniedPayload(GuardStage stage) {
        switch (stage) {
            case AGENT_INPUT:
                return AgentInput.of("DENIED");
            case MODEL_INPUT:
                return ModelRequest.builder()
                        .messages(Java8.list(GuardMessage.user("DENIED")))
                        .build();
            case MODEL_OUTPUT:
                return ModelResponse.of(GuardMessage.assistant("DENIED"));
            case TOOL_INPUT:
                return ToolCall.of("c", "lookup", "{\"value\":\"DENIED\"}");
            case TOOL_OUTPUT:
                return ToolResult.of("c", "lookup", "DENIED");
            case AGENT_OUTPUT:
                return AgentOutput.of("DENIED");
            default:
                throw new IllegalStateException("Unknown stage");
        }
    }
}
