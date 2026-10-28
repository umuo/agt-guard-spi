package org.agentguard.agt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.agentguard.context.GuardContext;
import org.agentguard.context.StandardAttributes;
import org.agentguard.engine.GuardEngine;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.model.AgentInput;
import org.agentguard.model.GuardMessage;
import org.agentguard.model.ModelResponse;
import org.agentguard.model.ToolCall;
import org.agentguard.model.ToolResult;
import org.junit.Test;

/**
 * 通过实际策略评估验证公共配置组合、批量原子性及工具快捷授权。
 * 配置错误只定位字段，不回显匹配词或替换内容，失败后仍可继续配置。
 */
public class AgtPolicyConfigurationTest {
    private static GuardContext context(boolean permitted) {
        return GuardContext.builder()
                .agentId("demo")
                .tenantId("tenant-a")
                .userId("user-a")
                .attributes(
                        Collections.singletonMap(
                                StandardAttributes.PERMISSIONS,
                                permitted
                                        ? Collections.singleton("orders:read")
                                        : Collections.emptySet()))
                .build();
    }

    private static void blocked(GuardEngine engine, ToolCall call, boolean permitted, String code) {
        GuardBlockedException error =
                assertThrows(
                        GuardBlockedException.class,
                        () -> engine.checkToolInput(call, context(permitted)).payloadOrThrow());
        assertEquals(code, error.findings().get(0).code());
    }

    @Test
    public void reusesCommonConfigurationAcrossIndependentEngines() {
        Consumer<AgtGuards.Builder> common =
                guard ->
                        guard.denyText("DENIED")
                                .maskInput(Collections.singletonMap("PRIVATE", "MASKED"));
        GuardEngine first =
                AgtGuards.builder()
                        .configure(common)
                        .tenantTool(
                                "lookup",
                                "orders:read",
                                "tenant",
                                tool -> tool.requireStrings("id"))
                        .build();
        GuardEngine second =
                AgtGuards.builder()
                        .configure(common)
                        .tenantTool(
                                "search",
                                "orders:read",
                                "tenant",
                                tool -> tool.requireStrings("query"))
                        .build();
        for (GuardEngine engine : new GuardEngine[] {first, second}) {
            assertEquals(
                    "MASKED",
                    engine.checkAgentInput(AgentInput.of("PRIVATE"), context(true))
                            .payloadOrThrow()
                            .text());
            assertThrows(
                    GuardBlockedException.class,
                    () ->
                            engine.checkAgentInput(AgentInput.of("DENIED"), context(true))
                                    .payloadOrThrow());
        }
        ToolCall lookup = ToolCall.of("c", "lookup", "{\"tenant\":\"tenant-a\",\"id\":\"one\"}");
        assertEquals(lookup, first.checkToolInput(lookup, context(true)).payloadOrThrow());
        blocked(second, lookup, true, "tool_not_allowed");
    }

    @Test
    public void failedConfigurationRollsBackVersionRulesToolsAndGeneratedIds() {
        AgtGuards.Builder builder = AgtGuards.builder().version("original").denyText("DENIED");
        IllegalStateException cause = new IllegalStateException("application callback failed");
        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                builder.configure(
                                        draft -> {
                                            draft.version("changed")
                                                    .denyText("NEW")
                                                    .tool("lookup", tool -> {});
                                            throw cause;
                                        }));
        assertSame(cause, thrown);
        assertEquals("original", builder.buildPolicy().version());
        assertEquals(1, builder.buildPolicy().contentRules().size());
        GuardEngine engine = builder.build();
        assertTrue(engine.checkAgentInput(AgentInput.of("NEW"), context(true)).isProceed());
        blocked(engine, ToolCall.of("c", "lookup", "{}"), true, "tool_not_allowed");
        builder.tool("lookup", tool -> {}).denyText("LATER");
        assertEquals("agt.fluent.rule.2", builder.buildPolicy().contentRules().get(1).id());
    }

    @Test
    public void capturedDraftDoesNotMutateAppliedSnapshot() {
        AtomicReference<AgtGuards.Builder> captured = new AtomicReference<>();
        AgtGuards.Builder builder =
                AgtGuards.builder()
                        .configure(
                                draft -> {
                                    captured.set(draft);
                                    draft.denyText("DENIED")
                                            .configure(nested -> nested.version("nested"));
                                });
        captured.get().denyText("LATE").version("late");
        assertEquals("nested", builder.buildPolicy().version());
        assertEquals(1, builder.buildPolicy().contentRules().size());
        assertTrue(
                builder.build().checkAgentInput(AgentInput.of("LATE"), context(true)).isProceed());
    }

    @Test
    public void batchMasksPreserveOrderScopeAndCallerMapSnapshot() {
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("one", "two");
        replacements.put("two", "three");
        GuardEngine engine =
                AgtGuards.builder()
                        .maskInput(replacements)
                        .maskToolOutput(Collections.singletonMap("one", "tool-masked"))
                        .build();
        replacements.clear();
        assertEquals(
                "three",
                engine.checkAgentInput(AgentInput.of("one"), context(true))
                        .payloadOrThrow()
                        .text());
        assertEquals(
                "one",
                engine.checkModelOutput(
                                ModelResponse.of(GuardMessage.assistant("one")), context(true))
                        .payloadOrThrow()
                        .text());
        assertEquals(
                "tool-masked",
                engine.checkToolOutput(ToolResult.of("c", "lookup", "one"), context(true))
                        .payloadOrThrow()
                        .content());
    }

    @Test
    public void lateBatchConflictDoesNotInstallEarlierMasksOrConsumeIds() {
        AgtGuards.Builder builder = AgtGuards.builder().maskInput("existing", "masked");
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("fresh", "changed");
        replacements.put("existing", "conflict");
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> builder.maskInput(replacements));
        assertTrue(error.getMessage().contains("replacements"));
        assertEquals(1, builder.buildPolicy().contentRules().size());
        assertEquals(
                "fresh",
                builder.build()
                        .checkAgentInput(AgentInput.of("fresh"), context(true))
                        .payloadOrThrow()
                        .text());
        builder.denyText("DENIED");
        assertEquals("agt.fluent.rule.2", builder.buildPolicy().contentRules().get(1).id());
    }

    @Test
    public void nullBatchTargetDoesNotLeavePartialConfigurationOrLeakPatterns() {
        AgtGuards.Builder builder = AgtGuards.builder().denyText("DENIED");
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("private-source", "private-target");
        replacements.put("another-private-source", null);
        NullPointerException error =
                assertThrows(NullPointerException.class, () -> builder.maskInput(replacements));
        assertTrue(error.getMessage().contains("AGENT_INPUT.replacements.target"));
        assertFalse(error.getMessage().contains("private-source"));
        assertEquals(1, builder.buildPolicy().contentRules().size());
    }

    @Test
    public void tenantShortcutKeepsPermissionsBindingAndClosedArguments() {
        GuardEngine engine =
                AgtGuards.builder()
                        .tenantTool(
                                "lookup",
                                "orders:read",
                                "tenant",
                                tool ->
                                        tool.requireStrings("id", "kind")
                                                .optionalStrings("note", "label"))
                        .build();
        ToolCall valid =
                ToolCall.of(
                        "c",
                        "lookup",
                        "{\"tenant\":\"tenant-a\",\"id\":\"one\",\"kind\":\"order\"}");
        assertEquals(valid, engine.checkToolInput(valid, context(true)).payloadOrThrow());
        blocked(engine, valid, false, "permission_denied");
        blocked(
                engine,
                valid.withArguments("{\"tenant\":\"tenant-b\",\"id\":\"one\",\"kind\":\"order\"}"),
                true,
                "context_binding_denied");
        blocked(
                engine,
                valid.withArguments("{\"tenant\":\"tenant-a\",\"id\":\"one\"}"),
                true,
                "tool_arguments_denied");
        blocked(
                engine,
                valid.withArguments(
                        "{\"tenant\":\"tenant-a\",\"id\":\"one\",\"kind\":\"order\",\"note\":1}"),
                true,
                "tool_arguments_denied");
        blocked(
                engine,
                valid.withArguments(
                        "{\"tenant\":\"tenant-a\",\"id\":\"one\",\"kind\":\"order\",\"extra\":\"x\"}"),
                true,
                "tool_arguments_denied");
    }

    @Test
    public void failedStringBatchesDoNotLeaveRequiredOrOptionalParameters() {
        GuardEngine engine =
                AgtGuards.builder()
                        .tool(
                                "lookup",
                                tool -> {
                                    assertThrows(
                                            IllegalArgumentException.class,
                                            () -> tool.requireStrings("first", "first"));
                                    assertThrows(
                                            IllegalArgumentException.class,
                                            () -> tool.optionalStrings("extra", "bad name"));
                                    tool.optionalString("note");
                                })
                        .build();
        ToolCall empty = ToolCall.of("c", "lookup", "{}");
        assertEquals(empty, engine.checkToolInput(empty, context(true)).payloadOrThrow());
        blocked(engine, empty.withArguments("{\"extra\":\"x\"}"), true, "tool_arguments_denied");
    }

    @Test
    public void failedPermissionBatchDoesNotAddUnintendedPermissions() {
        GuardEngine engine =
                AgtGuards.builder()
                        .tool(
                                "lookup",
                                tool -> {
                                    tool.requirePermission("orders:read");
                                    assertThrows(
                                            IllegalArgumentException.class,
                                            () ->
                                                    tool.requirePermission(
                                                            "orders:write", "orders:write"));
                                    assertThrows(
                                            IllegalArgumentException.class,
                                            () ->
                                                    tool.requirePermission(
                                                            "other:read", "bad permission"));
                                })
                        .build();
        ToolCall empty = ToolCall.of("c", "lookup", "{}");
        assertEquals(empty, engine.checkToolInput(empty, context(true)).payloadOrThrow());
        blocked(engine, empty, false, "permission_denied");
    }

    @Test
    public void errorsIdentifyConfigurationLocationsAndKeepSensitiveValuesOut() {
        IllegalArgumentException parameter =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                AgtGuards.builder()
                                        .tenantTool(
                                                "lookup",
                                                "orders:read",
                                                "tenant",
                                                tool -> tool.requireString("tenant")));
        assertTrue(parameter.getMessage().contains("tools.lookup.parameters.tenant"));
        assertTrue(parameter.getMessage().contains("bindTenant"));
        IllegalArgumentException identifier =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> AgtGuards.builder().version("private invalid value"));
        assertTrue(identifier.getMessage().startsWith("version:"));
        assertFalse(identifier.getMessage().contains("private invalid value"));
        IllegalArgumentException conflict =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                AgtGuards.builder()
                                        .maskInput("private-pattern", "private-target")
                                        .maskInput("private-pattern", "other-target"));
        assertTrue(conflict.getMessage().contains("contentRules."));
        assertFalse(conflict.getMessage().contains("private-pattern"));
        assertFalse(conflict.getMessage().contains("private-target"));
    }

    @Test
    public void invalidShortcutOrBatchDoesNotPublishToolAndEmptyConfigurationStaysRejected() {
        AgtGuards.Builder builder = AgtGuards.builder().denyText("DENIED");
        assertThrows(
                IllegalArgumentException.class,
                () -> builder.tenantTool("lookup", "bad permission", "tenant", tool -> {}));
        builder.tenantTool("lookup", "orders:read", "tenant", tool -> tool.requireStrings("id"));
        assertThrows(
                IllegalArgumentException.class, () -> builder.maskInput(Collections.emptyMap()));
        assertThrows(NullPointerException.class, () -> builder.configure(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgtGuards.builder().tool("another", tool -> tool.requireStrings()));
        assertThrows(
                IllegalStateException.class,
                () -> AgtGuards.builder().configure(guard -> {}).build());
    }
}
