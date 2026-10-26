import io.agt.core.AgentControl;
import io.agt.core.BudgetLimits;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.InterventionPoint;
import io.agt.core.JsonValues;
import io.agt.core.PolicyDecision;
import io.agt.core.PolicyRequest;
import io.agt.core.TrustedContext;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.agentguard.context.GuardContext;
import org.agentguard.core.GuardEngineBuilder;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardDecision;
import org.agentguard.decision.RiskCategory;
import org.agentguard.decision.RiskLevel;
import org.agentguard.engine.GuardEngine;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.model.AgentInput;
import org.agentguard.model.ToolCall;
import org.agentguard.spi.AgentInputGuard;
import org.agentguard.spi.ToolInputGuard;

/**
 * 验证 AGT 策略桥接与完整执行治理的边界，仅供本地 spike。
 * 只投影纯文本输入和工具 JSON 参数，不代表六阶段生产适配已完成。
 */
public final class AgtBridgeSpike {
    private static int passed;

    public static void main(String[] args) {
        StructuredPolicy policy = policy(false, false);
        GuardEngine engine = engine(policy);
        GuardContext context = context();
        require(engine.checkAgentInput(AgentInput.of("hello"), context).payloadOrThrow()
                .text().equals("hello"), "正常输入放行");
        expect(GuardBlockedException.class, () -> engine.checkAgentInput(
                AgentInput.of("DENIED"), context).payloadOrThrow(), "内容规则阻断");
        require(engine.checkAgentInput(AgentInput.of("SECRET"), context).payloadOrThrow()
                .text().equals("MASKED"), "改写后的输入交给调用方");
        require(engine.checkToolInput(tool("t1"), context).payloadOrThrow()
                .arguments().contains("t1"), "可信租户与权限匹配");
        expect(GuardBlockedException.class, () -> engine.checkToolInput(tool("t2"), context)
                .payloadOrThrow(), "伪造租户阻断");
        GuardContext noPermission = context.toBuilder().attributes(Map.of()).build();
        expect(GuardBlockedException.class, () -> engine.checkToolInput(tool("t1"), noPermission)
                .payloadOrThrow(), "缺少权限阻断");
        expect(GuardBlockedException.class, () -> engine.checkToolInput(
                ToolCall.of("call1", "unknown", "{}"), context).payloadOrThrow(), "未知工具阻断");
        expect(GuardExecutionException.class, () -> engine.checkToolInput(
                ToolCall.of("call1", "lookup", "{\"tenantId\":\"t1\",\"tenantId\":\"t2\"}"), context)
                .payloadOrThrow(), "重复 JSON 键按故障关闭");
        expect(GuardBlockedException.class, () -> engine(policy(true, false))
                .checkToolInput(tool("t1"), context).payloadOrThrow(), "策略桥接不支持审批时拒绝");
        expect(GuardBlockedException.class, () -> engine(policy(false, true))
                .checkToolInput(tool("t1"), context).payloadOrThrow(), "改写后重新校验租户绑定");
        expect(GovernanceException.class, () -> new PolicyRequest(InterventionPoint.INPUT,
                trusted(context), "session1", "action1", null, AgentInput.of("hello")),
                "不能直接传入 Guard DTO");
        verifyRuntime();
        System.out.println("Completed " + passed + " checks");
    }

    private static void verifyRuntime() {
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        try (AgentControl control = AgentControl.builder(policy(true, false))
                .audit(record -> { })
                .approvalResolver(request -> {
                    approvals.incrementAndGet();
                    return CompletableFuture.completedFuture(ApprovalResponse.allow(request));
                }).build()) {
            try (HostSession session = control.openDetachedSession(trusted(context()))) {
                for (int i = 0; i < 2; i++) {
                    session.evaluate(InterventionPoint.PRE_TOOL_CALL, "evaluate" + i, "lookup",
                            Map.of("tenantId", "t1"));
                }
                require(approvals.get() == 2, "HostSession.evaluate 可驱动审批");
                require(session.budgetSnapshot().toolCalls() == 0,
                        "evaluate 两次仍未扣减一次工具预算");
            }
            try (HostSession session = control.openDetachedSession(trusted(context()))) {
                Function<Object, String> execute = effective -> {
                    executions.incrementAndGet();
                    return "ok";
                };
                session.operation(true, "lookup", Map.of("tenantId", "t1"), execute,
                        value -> value, (original, effective) -> (String) effective);
                require(session.budgetSnapshot().toolCalls() == 1, "operation 实际扣减预算");
                expect(GovernanceException.class, () -> session.operation(true, "lookup",
                        Map.of("tenantId", "t1"), execute, value -> value,
                        (original, effective) -> (String) effective), "第二次工具执行受预算阻断");
                require(executions.get() == 1, "预算拒绝时未执行第二次副作用");
            }
        }
    }

    private static StructuredPolicy policy(boolean approval, boolean rewriteTenant) {
        StructuredPolicy.Builder builder = StructuredPolicy.builder("spike-v1")
                .budget(new BudgetLimits(1, 1, 300_000))
                .tool("lookup", new ToolRule(Set.of("lookup:read"), ParameterRule.object(
                        Map.of("tenantId", ParameterRule.string("tenantId")), "tenantId"), approval))
                .content(new ContentRule("content-rule", Set.of(InterventionPoint.INPUT),
                        List.of("DENIED"), Map.of("SECRET", "MASKED")));
        if (rewriteTenant) {
            builder.content(new ContentRule("tenant-rewrite", Set.of(InterventionPoint.PRE_TOOL_CALL),
                    List.of(), Map.of("t1", "t2")));
        }
        return builder.build();
    }

    private static GuardEngine engine(StructuredPolicy policy) {
        return new GuardEngineBuilder().addGuard(new InputBridge(policy))
                .addGuard(new ToolBridge(policy)).build();
    }

    private static ToolCall tool(String tenant) {
        return ToolCall.of("call1", "lookup", JsonValues.write(Map.of("tenantId", tenant)));
    }

    private static GuardContext context() {
        return GuardContext.builder().agentId("agent1").tenantId("t1").userId("user1")
                .sessionId("session1").attributes(Map.of("agentguard.permissions", Set.of("lookup:read")))
                .build();
    }

    private static TrustedContext trusted(GuardContext context) {
        Object raw = context.attributes().get("agentguard.permissions");
        Set<String> permissions = new java.util.HashSet<>();
        if (raw != null) {
            if (!(raw instanceof Set<?> values)) {
                throw new IllegalArgumentException("Invalid trusted permissions");
            }
            for (Object value : values) {
                if (!(value instanceof String permission)) {
                    throw new IllegalArgumentException("Invalid trusted permission");
                }
                permissions.add(permission);
            }
        }
        return new TrustedContext(context.agentId(), context.tenantId(), context.userId(), permissions);
    }

    private static <T> GuardDecision<T> evaluate(StructuredPolicy policy, PolicyRequest request,
                                               Function<Object, T> restore) {
        PolicyDecision decision = policy.evaluate(request);
        if (decision.kind() == PolicyDecision.Kind.DENY) {
            return GuardDecision.block(finding(decision.reasonCode()));
        }
        Object effective = decision.kind() == PolicyDecision.Kind.TRANSFORM
                ? decision.replacement() : request.target();
        try {
            policy.validateEffective(request.withTarget(effective));
        } catch (GovernanceException denied) {
            return GuardDecision.block(finding(denied.code()));
        }
        if (policy.requiresApproval(request.withTarget(effective))) {
            return GuardDecision.block(finding("approval_unsupported"));
        }
        if (decision.kind() == PolicyDecision.Kind.TRANSFORM) {
            return GuardDecision.modify(restore.apply(effective), finding(decision.reasonCode()));
        }
        return GuardDecision.pass();
    }

    private static Finding finding(String code) {
        return Finding.of(code, RiskCategory.COMPLIANCE, RiskLevel.HIGH);
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
        System.out.println("PASS " + (++passed) + " " + label);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action, String label) {
        try {
            action.run();
        } catch (Throwable error) {
            if (!type.isInstance(error)) {
                throw new AssertionError(label, error);
            }
            require(true, label);
            return;
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
    }

    /**
     * 将纯文本入口投影为 AGT 支持的字符串，并恢复原有输入封装。
     */
    private static final class InputBridge implements AgentInputGuard {
        private final StructuredPolicy policy;

        private InputBridge(StructuredPolicy policy) {
            this.policy = policy;
        }

        @Override
        public String id() {
            return "agt.input";
        }

        @Override
        public GuardDecision<AgentInput> check(AgentInput input, GuardContext context) {
            PolicyRequest request = new PolicyRequest(InterventionPoint.INPUT, trusted(context),
                    context.sessionId(), UUID.randomUUID().toString(), null, input.text());
            return evaluate(policy, request, value -> input.withText((String) value));
        }
    }

    /**
     * 将工具参数解析为有界 JSON 对象，工具身份始终来自原调用。
     */
    private static final class ToolBridge implements ToolInputGuard {
        private final StructuredPolicy policy;

        private ToolBridge(StructuredPolicy policy) {
            this.policy = policy;
        }

        @Override
        public String id() {
            return "agt.tool-input";
        }

        @Override
        public GuardDecision<ToolCall> check(ToolCall input, GuardContext context) {
            PolicyRequest request = new PolicyRequest(InterventionPoint.PRE_TOOL_CALL, trusted(context),
                    context.sessionId(), UUID.randomUUID().toString(), input.toolName(),
                    JsonValues.parseObject(input.arguments()));
            return evaluate(policy, request, value -> input.withArguments(JsonValues.write(value)));
        }
    }
}
