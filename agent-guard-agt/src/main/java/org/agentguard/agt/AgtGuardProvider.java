package org.agentguard.agt;

import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.Java8;
import io.agt.core.PolicyDecision;
import io.agt.core.PolicyEvaluator;
import io.agt.core.PolicyRequest;
import io.agt.core.TrustedContext;
import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.context.StandardAttributes;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardDecision;
import org.agentguard.decision.RiskCategory;
import org.agentguard.decision.RiskLevel;
import org.agentguard.spi.Guard;

/**
 * 通过中立六阶段 SPI 发布 AGT 策略，不创建会话运行时或执行模型和工具。
 * 自定义评估器必须线程安全，宿主负责最终参数授权顺序及可信身份注入。
 */
public final class AgtGuardProvider implements org.agentguard.streaming.StreamingGuardProvider {
    private final PolicyEvaluator evaluator;
    public static final String POLICY_PATH_PROPERTY = "agentguard.agt.policy-path";
    public static final String POLICY_PATH_ENV = "AGENTGUARD_AGT_POLICY_PATH";
    private final List<Guard<?>> guards;

    public AgtGuardProvider() {
        this(configuredPolicy());
    }

    public AgtGuardProvider(PolicyEvaluator evaluator) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        List<Guard<?>> configured = new ArrayList<>();
        for (GuardStage stage : GuardStage.values()) {
            InterventionPoint point = point(stage);
            if (!evaluator.interventionPoints().contains(point)) {
                throw new IllegalArgumentException(
                        "AGT policy must configure all six guard stages");
            }
            configured.add(new PolicyGuard(stage, point, evaluator));
        }
        guards = Java8.copyList(configured);
    }

    private static StructuredPolicy configuredPolicy() {
        String path = System.getProperty(POLICY_PATH_PROPERTY);
        if (path == null) {
            path = System.getenv(POLICY_PATH_ENV);
        }
        if (path == null || Java8.isBlank(path)) {
            throw new IllegalStateException(
                    "Configure " + POLICY_PATH_PROPERTY + " or " + POLICY_PATH_ENV);
        }
        return StructuredPolicy.fromPath(Paths.get(path));
    }

    @Override
    public void validateWindow(org.agentguard.streaming.GuardStreamOptions options) {
        try (AgtWindowStream ignored =
                new AgtWindowStream(evaluator, options, text -> {}, error -> {})) {
            // 构造阶段验证全部输出规则、词表容量与窗口上限，不启动线程。
        }
    }

    @Override
    public org.agentguard.streaming.GuardStream openWindow(
            org.agentguard.streaming.GuardStreamOptions options,
            GuardContext context,
            java.util.function.Consumer<String> output,
            java.util.function.Consumer<Throwable> failure) {
        trusted(context);
        return new AgtWindowStream(evaluator, options, output, failure);
    }

    @Override
    public String name() {
        return "agt";
    }

    @Override
    public Collection<? extends Guard<?>> guards() {
        return guards;
    }

    static InterventionPoint point(GuardStage stage) {
        switch (stage) {
            case AGENT_INPUT:
                return InterventionPoint.INPUT;
            case MODEL_INPUT:
                return InterventionPoint.PRE_MODEL_CALL;
            case MODEL_OUTPUT:
                return InterventionPoint.POST_MODEL_CALL;
            case TOOL_INPUT:
                return InterventionPoint.PRE_TOOL_CALL;
            case TOOL_OUTPUT:
                return InterventionPoint.POST_TOOL_CALL;
            case AGENT_OUTPUT:
                return InterventionPoint.OUTPUT;
            default:
                throw new IllegalStateException("Unknown enum value");
        }
    }

    private static TrustedContext trusted(GuardContext context) {
        Set<String> permissions = new HashSet<>();
        Object raw = context.attributes().get(StandardAttributes.PERMISSIONS);
        if (raw != null) {
            if (!(raw instanceof Set<?>)) {
                throw new IllegalArgumentException("Trusted permissions must be a set of strings");
            }
            Set<?> values = (Set<?>) raw;
            for (Object value : values) {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("Trusted permission must be a string");
                }
                String permission = (String) value;
                permissions.add(permission);
            }
        }
        return new TrustedContext(
                context.agentId(), context.tenantId(), context.userId(), permissions);
    }

    private static Finding finding(String code) {
        return Finding.of(code, RiskCategory.COMPLIANCE, RiskLevel.MEDIUM);
    }

    /**
     * 单阶段策略桥接，使用请求内共享状态区分治理调用与业务长会话。
     */
    private static final class PolicyGuard implements Guard<Object> {
        private final GuardStage stage;
        private final InterventionPoint point;
        private final PolicyEvaluator evaluator;

        private PolicyGuard(GuardStage stage, InterventionPoint point, PolicyEvaluator evaluator) {
            this.stage = stage;
            this.point = point;
            this.evaluator = evaluator;
        }

        @Override
        public String id() {
            return "agt." + point.wireName();
        }

        @Override
        public GuardStage stage() {
            return stage;
        }

        @Override
        public int order() {
            return 1000;
        }

        @Override
        public GuardDecision<Object> check(Object payload, GuardContext context) {
            TrustedContext identity = trusted(context);
            AgtPayloads.Projection projection = AgtPayloads.project(stage, payload);
            String session =
                    (String)
                            context.state()
                                    .computeIfAbsent(
                                            "org.agentguard.agt.invocation",
                                            key -> UUID.randomUUID().toString());
            PolicyRequest request =
                    new PolicyRequest(
                            point,
                            identity,
                            session,
                            UUID.randomUUID().toString(),
                            projection.toolName(),
                            projection.target());
            PolicyDecision decision =
                    Objects.requireNonNull(evaluator.evaluate(request), "AGT decision");
            if (decision.approvalRequired()) {
                return GuardDecision.block("此操作需要审批，当前接入未启用审批", finding("approval_unsupported"));
            }
            if (decision.kind() == PolicyDecision.Kind.DENY) {
                return GuardDecision.block("操作未通过安全策略", finding(decision.reasonCode()));
            }
            Object effective =
                    decision.kind() == PolicyDecision.Kind.TRANSFORM
                            ? decision.replacement()
                            : request.target();
            PolicyRequest effectiveRequest = request.withTarget(effective);
            try {
                evaluator.validateEffective(effectiveRequest);
            } catch (GovernanceException denied) {
                return GuardDecision.block("操作未通过安全策略", finding(denied.code()));
            }
            if (evaluator.requiresApproval(effectiveRequest)) {
                return GuardDecision.block("此操作需要审批，当前接入未启用审批", finding("approval_unsupported"));
            }
            if (decision.kind() == PolicyDecision.Kind.TRANSFORM) {
                return GuardDecision.modify(
                        projection.restore().apply(effective), finding(decision.reasonCode()));
            }
            return GuardDecision.pass();
        }
    }
}
