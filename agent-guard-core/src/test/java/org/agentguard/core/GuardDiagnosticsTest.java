package org.agentguard.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.decision.GuardDecision;
import org.agentguard.diagnostics.DiagnosableGuardEngine;
import org.agentguard.diagnostics.GuardDiagnostics;
import org.agentguard.engine.GuardEngine;
import org.agentguard.engine.GuardOutcome;
import org.agentguard.model.AgentInput;
import org.agentguard.spi.AgentInputGuard;
import org.agentguard.spi.GuardProvider;
import org.junit.Assert;
import org.junit.Test;

/**
 * 验证诊断排序、快照隔离和未知能力，确保元信息读取不执行安全检查。
 * 使用会抛错的请求入口证明诊断不会调用策略解析、上下文过滤或检测。
 */
public class GuardDiagnosticsTest {
    @Test
    public void preservesExecutionOrderAndAllStagesWithoutExecutingGuards() {
        GuardEngine engine = new GuardEngineBuilder()
                .addGuard(new MetadataGuard("z", 1))
                .addGuard(new MetadataGuard("b", 0))
                .addGuard(new MetadataGuard("a", 0))
                .policyResolver((guard, context) -> {
                    throw new AssertionError("must not resolve policy");
                }).build();
        GuardDiagnostics report = GuardDiagnostics.inspect(engine);
        Assert.assertTrue(report.detailsAvailable());
        Assert.assertTrue(report.dynamicPolicy());
        Assert.assertTrue(report.streaming());
        Assert.assertEquals(6, report.rulesByStage().size());
        List<GuardDiagnostics.Rule> rules = report.rulesByStage().get(GuardStage.AGENT_INPUT);
        Assert.assertEquals(Arrays.asList("a", "b", "z"),
                Arrays.asList(rules.get(0).id(), rules.get(1).id(), rules.get(2).id()));
        Assert.assertEquals(1, rules.get(2).order());
        Assert.assertTrue(report.rulesByStage().get(GuardStage.TOOL_INPUT).isEmpty());
        Assert.assertThrows(UnsupportedOperationException.class, () -> rules.clear());
        Assert.assertThrows(UnsupportedOperationException.class, () -> report.rulesByStage().clear());
    }

    @Test
    public void defensivelyCopiesMetadataAndDoesNotPrintUntrustedLabels() {
        List<GuardDiagnostics.Provider> providers = new ArrayList<GuardDiagnostics.Provider>();
        GuardDiagnostics.Provider provider = new GuardDiagnostics.Provider("private-label", "private-type",
                GuardDiagnostics.PolicySource.UNKNOWN, false);
        providers.add(provider);
        List<GuardDiagnostics.Rule> rules = new ArrayList<GuardDiagnostics.Rule>();
        GuardDiagnostics.Rule rule = new GuardDiagnostics.Rule("private-rule", GuardStage.AGENT_INPUT, 7);
        rules.add(rule);
        GuardDiagnostics report = new GuardDiagnostics("private-engine", true, false, false, providers, rules);
        providers.clear();
        rules.clear();
        Assert.assertEquals(1, report.providers().size());
        Assert.assertEquals(1, report.rulesByStage().get(GuardStage.AGENT_INPUT).size());
        Assert.assertThrows(UnsupportedOperationException.class, () -> report.providers().clear());
        Assert.assertFalse(report.toString().contains("private"));
        Assert.assertFalse(provider.toString().contains("private"));
        Assert.assertFalse(rule.toString().contains("private"));
    }

    @Test
    public void customEngineIsUnknownInsteadOfClaimingZeroRules() {
        GuardEngine custom = new UnknownEngine();
        GuardDiagnostics report = GuardDiagnostics.inspect(custom);
        Assert.assertFalse(report.detailsAvailable());
        Assert.assertFalse(report.streaming());
        Assert.assertEquals(UnknownEngine.class.getName(), report.engineType());
    }

    @Test
    public void brokenOrNullDiagnosticsDoNotChangeTheEngine() {
        GuardDiagnostics report = GuardDiagnostics.inspect(new BrokenDiagnosticsEngine());
        Assert.assertFalse(report.detailsAvailable());
        Assert.assertFalse(GuardDiagnostics.inspect(new NullDiagnosticsEngine()).detailsAvailable());
    }

    @Test
    public void emptyReferenceEngineIsKnownAndBuilderChangesDoNotMutateSnapshot() {
        GuardEngineBuilder builder = new GuardEngineBuilder();
        GuardDiagnostics before = GuardDiagnostics.inspect(builder.build());
        builder.addGuard(new MetadataGuard("later", 0));
        GuardDiagnostics after = GuardDiagnostics.inspect(builder.build());
        Assert.assertTrue(before.detailsAvailable());
        Assert.assertFalse(before.dynamicPolicy());
        Assert.assertTrue(before.rulesByStage().get(GuardStage.AGENT_INPUT).isEmpty());
        Assert.assertEquals(1, after.rulesByStage().get(GuardStage.AGENT_INPUT).size());
    }

    @Test
    public void thirdPartyProviderDefaultsToUnknownPolicySource() {
        GuardProvider provider = new GuardProvider() {
            @Override
            public String name() {
                return "custom";
            }

            @Override
            public java.util.Collection<? extends org.agentguard.spi.Guard<?>> guards() {
                return Collections.singletonList(new MetadataGuard("custom.rule", 0));
            }
        };
        GuardDiagnostics report = GuardDiagnostics.inspect(new GuardEngineBuilder().addProvider(provider).build());
        Assert.assertEquals("custom", report.providers().get(0).name());
        Assert.assertEquals(GuardDiagnostics.PolicySource.UNKNOWN, report.providers().get(0).policySource());
    }

    /**
     * 只允许读取排序元信息，检测和上下文过滤均不应由诊断触发。
     */
    private static final class MetadataGuard implements AgentInputGuard {
        private final String id;
        private final int order;

        private MetadataGuard(String id, int order) {
            this.id = id;
            this.order = order;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public boolean supports(GuardContext context) {
            throw new AssertionError("must not evaluate context");
        }

        @Override
        public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
            throw new AssertionError("must not check payload");
        }
    }

    /**
     * 没有实现可选诊断能力的第三方引擎。
     */
    private static class UnknownEngine implements GuardEngine {
        @Override
        public <T> GuardOutcome<T> check(GuardStage stage, T payload, GuardContext context) {
            throw new AssertionError("must not call engine");
        }
    }

    /**
     * 模拟诊断提供者故障，避免诊断改变安全执行行为。
     */
    private static final class BrokenDiagnosticsEngine extends UnknownEngine implements DiagnosableGuardEngine {
        @Override
        public GuardDiagnostics diagnostics() {
            throw new IllegalStateException("private-exception-message");
        }
    }

    /**
     * 模拟返回空快照的第三方实现，按详情未知处理。
     */
    private static final class NullDiagnosticsEngine extends UnknownEngine implements DiagnosableGuardEngine {
        @Override
        public GuardDiagnostics diagnostics() {
            return null;
        }
    }
}
