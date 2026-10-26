package org.agentguard.core;

import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.decision.EnforcementMode;
import org.agentguard.decision.FailPolicy;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardAction;
import org.agentguard.decision.GuardDecision;
import org.agentguard.decision.GuardPolicy;
import org.agentguard.decision.RiskCategory;
import org.agentguard.decision.RiskLevel;
import org.agentguard.engine.GuardEngine;
import org.agentguard.engine.GuardOutcome;
import org.agentguard.event.GuardEvent;
import org.agentguard.event.GuardEventListener;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.model.AgentInput;
import org.agentguard.model.ToolCall;
import org.agentguard.spi.AgentInputGuard;
import org.agentguard.spi.Guard;
import org.agentguard.spi.GuardPolicyResolver;
import org.agentguard.spi.ToolInputGuard;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultGuardEngineTest {

    private final GuardContext testContext = GuardContext.builder()
            .traceId("trace-001")
            .tenantId("tenant-corp-a")
            .userId("user-100")
            .build();

    @Test
    public void testPassThrough() {
        AgentInputGuard passGuard = new AgentInputGuard() {
            @Override
            public String id() {
                return "test.pass";
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
                return GuardDecision.pass();
            }
        };

        GuardEngine engine = DefaultGuardEngine.builder().addGuard(passGuard).build();
        AgentInput input = AgentInput.of("Hello world");

        GuardOutcome<AgentInput> outcome = engine.checkAgentInput(input, testContext);

        Assert.assertTrue(outcome.isProceed());
        Assert.assertEquals(GuardAction.PASS, outcome.action());
        Assert.assertEquals("Hello world", outcome.payloadOrThrow().text());
        Assert.assertTrue(outcome.findings().isEmpty());
    }

    @Test
    public void testModifyChain() {
        AgentInputGuard maskGuard1 = new AgentInputGuard() {
            @Override
            public String id() {
                return "test.mask.phone";
            }

            @Override
            public int order() {
                return 1;
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
                String masked = payload.text().replace("13800000000", "[PHONE]");
                Finding finding = Finding.of("PHONE_MASKED", RiskCategory.SENSITIVE_DATA, RiskLevel.LOW);
                return GuardDecision.modify(payload.withText(masked), finding);
            }
        };

        AgentInputGuard appendGuard2 = new AgentInputGuard() {
            @Override
            public String id() {
                return "test.append";
            }

            @Override
            public int order() {
                return 2;
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
                // 应该接收到上一个 Guard 改写后的内容
                Assert.assertTrue(payload.text().contains("[PHONE]"));
                return GuardDecision.pass();
            }
        };

        GuardEngine engine = DefaultGuardEngine.builder()
                .addGuard(maskGuard1)
                .addGuard(appendGuard2)
                .build();

        AgentInput input = AgentInput.of("My phone is 13800000000");
        GuardOutcome<AgentInput> outcome = engine.checkAgentInput(input, testContext);

        Assert.assertTrue(outcome.isProceed());
        Assert.assertTrue(outcome.isModified());
        Assert.assertEquals("My phone is [PHONE]", outcome.payloadOrThrow().text());
        Assert.assertEquals(1, outcome.findings().size());
        Assert.assertEquals("PHONE_MASKED", outcome.findings().get(0).code());
    }

    @Test
    public void testBlockAndShortCircuit() {
        final AtomicBoolean secondGuardCalled = new AtomicBoolean(false);

        AgentInputGuard blockGuard = new AgentInputGuard() {
            @Override
            public String id() {
                return "test.block";
            }

            @Override
            public int order() {
                return 1;
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
                Finding finding = Finding.builder()
                        .code("PROMPT_INJECTION")
                        .category(RiskCategory.PROMPT_INJECTION)
                        .level(RiskLevel.CRITICAL)
                        .message("检测到提示词注入攻击")
                        .build();
                return GuardDecision.block("请求已被安全系统拦截", finding);
            }
        };

        AgentInputGuard secondGuard = new AgentInputGuard() {
            @Override
            public String id() {
                return "test.second";
            }

            @Override
            public int order() {
                return 2;
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
                secondGuardCalled.set(true);
                return GuardDecision.pass();
            }
        };

        GuardEngine engine = DefaultGuardEngine.builder()
                .addGuard(blockGuard)
                .addGuard(secondGuard)
                .build();

        GuardOutcome<AgentInput> outcome = engine.checkAgentInput(AgentInput.of("ignore rules"), testContext);

        Assert.assertTrue(outcome.isBlocked());
        Assert.assertFalse(outcome.isProceed());
        Assert.assertFalse(secondGuardCalled.get()); // 验证短路生效
        Assert.assertEquals("请求已被安全系统拦截", outcome.reply());
        Assert.assertEquals(1, outcome.findings().size());

        try {
            outcome.payloadOrThrow();
            Assert.fail("Should throw GuardBlockedException");
        } catch (GuardBlockedException e) {
            Assert.assertEquals(GuardStage.AGENT_INPUT, e.stage());
            Assert.assertEquals("请求已被安全系统拦截", e.reply());
        }
    }

    @Test
    public void testObservationModeDoesNotBlock() {
        // Guard 内部判定为 BLOCK，但全局/策略下发为 EnforcementMode.OBSERVE（放行观察模式）
        AgentInputGuard strictGuard = new AgentInputGuard() {
            @Override
            public String id() {
                return "test.strict";
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
                Finding f = Finding.of("CONTENT_RISK", RiskCategory.CONTENT_SAFETY, RiskLevel.HIGH);
                return GuardDecision.block("拦截违规内容", f);
            }
        };

        GuardPolicyResolver observeResolver = new GuardPolicyResolver() {
            @Override
            public GuardPolicy resolve(Guard<?> guard, GuardContext context) {
                return GuardPolicy.of(EnforcementMode.OBSERVE, FailPolicy.FAIL_OPEN);
            }
        };

        GuardEngine engine = DefaultGuardEngine.builder()
                .addGuard(strictGuard)
                .policyResolver(observeResolver)
                .build();

        AgentInput input = AgentInput.of("sensitive text");
        GuardOutcome<AgentInput> outcome = engine.checkAgentInput(input, testContext);

        // 验证：流程正常放行，payload 原样返回，未抛异常，但保留审计发现
        Assert.assertTrue(outcome.isProceed());
        Assert.assertFalse(outcome.isBlocked());
        Assert.assertEquals(GuardAction.OBSERVE, outcome.action());
        Assert.assertEquals("sensitive text", outcome.payloadOrThrow().text());
        Assert.assertEquals(1, outcome.findings().size());
        Assert.assertEquals("CONTENT_RISK", outcome.findings().get(0).code());
        // 明细记录中：decided 是 BLOCK，effective 是 OBSERVE
        Assert.assertEquals(GuardAction.BLOCK, outcome.records().get(0).decidedAction());
        Assert.assertEquals(GuardAction.OBSERVE, outcome.records().get(0).effectiveAction());
    }

    @Test
    public void testToolTenantValidation() {
        ToolInputGuard toolTenantGuard = new ToolInputGuard() {
            @Override
            public String id() {
                return "tool.tenant.checker";
            }

            @Override
            public GuardDecision<ToolCall> check(ToolCall payload, GuardContext context) {
                // 模拟工具入参中的租户校验
                if (payload.arguments().contains("tenant_cross_access")) {
                    Finding finding = Finding.builder()
                            .code("TENANT_ISOLATION_VIOLATION")
                            .category(RiskCategory.PERMISSION)
                            .level(RiskLevel.CRITICAL)
                            .message("禁止越权访问其他租户资源")
                            .build();
                    return GuardDecision.block("越权操作被拒绝", finding);
                }
                return GuardDecision.pass();
            }
        };

        GuardEngine engine = DefaultGuardEngine.builder().addGuard(toolTenantGuard).build();

        ToolCall safeCall = ToolCall.of("call-1", "query_user", "{\"userId\": 123}");
        GuardOutcome<ToolCall> safeOutcome = engine.checkToolInput(safeCall, testContext);
        Assert.assertTrue(safeOutcome.isProceed());

        ToolCall unsafeCall = ToolCall.of("call-2", "query_data", "{\"tenant_cross_access\": true}");
        GuardOutcome<ToolCall> unsafeOutcome = engine.checkToolInput(unsafeCall, testContext);
        Assert.assertTrue(unsafeOutcome.isBlocked());
        Assert.assertEquals("越权操作被拒绝", unsafeOutcome.reply());
    }

    @Test
    public void testFailPolicyOpenVsClosed() {
        AgentInputGuard brokenGuard = new AgentInputGuard() {
            @Override
            public String id() {
                return "test.broken";
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
                throw new RuntimeException("SDK network timeout or crash");
            }
        };

        // 1. FAIL_OPEN 测试
        GuardPolicyResolver failOpenResolver = new GuardPolicyResolver() {
            @Override
            public GuardPolicy resolve(Guard<?> guard, GuardContext context) {
                return GuardPolicy.of(EnforcementMode.ENFORCE, FailPolicy.FAIL_OPEN);
            }
        };
        GuardEngine openEngine = DefaultGuardEngine.builder()
                .addGuard(brokenGuard)
                .policyResolver(failOpenResolver)
                .build();

        GuardOutcome<AgentInput> openOutcome = openEngine.checkAgentInput(AgentInput.of("hi"), testContext);
        Assert.assertTrue(openOutcome.isProceed()); // FAIL_OPEN 必须放行
        Assert.assertEquals(GuardAction.OBSERVE, openOutcome.action());
        Assert.assertEquals(RiskCategory.GUARD_ERROR, openOutcome.findings().get(0).category());

        // 2. FAIL_CLOSED 测试
        GuardPolicyResolver failClosedResolver = new GuardPolicyResolver() {
            @Override
            public GuardPolicy resolve(Guard<?> guard, GuardContext context) {
                return GuardPolicy.of(EnforcementMode.ENFORCE, FailPolicy.FAIL_CLOSED);
            }
        };
        GuardEngine closedEngine = DefaultGuardEngine.builder()
                .addGuard(brokenGuard)
                .policyResolver(failClosedResolver)
                .build();

        GuardOutcome<AgentInput> closedOutcome = closedEngine.checkAgentInput(AgentInput.of("hi"), testContext);
        Assert.assertTrue(closedOutcome.isBlocked()); // FAIL_CLOSED 必须阻断
    }

    @Test
    public void testEventListener() {
        final List<GuardEvent> capturedEvents = new ArrayList<GuardEvent>();

        AgentInputGuard guard = new AgentInputGuard() {
            @Override
            public String id() {
                return "test.audit";
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput payload, GuardContext context) {
                return GuardDecision.observe(Finding.of("SUSPICIOUS", RiskCategory.OTHER, RiskLevel.LOW));
            }
        };

        GuardEngine engine = DefaultGuardEngine.builder()
                .addGuard(guard)
                .addListener(new GuardEventListener() {
                    @Override
                    public void onEvent(GuardEvent event) {
                        capturedEvents.add(event);
                    }
                })
                .build();

        engine.checkAgentInput(AgentInput.of("ping"), testContext);

        Assert.assertEquals(1, capturedEvents.size());
        GuardEvent event = capturedEvents.get(0);
        Assert.assertEquals(GuardStage.AGENT_INPUT, event.stage());
        Assert.assertEquals("trace-001", event.context().traceId());
        Assert.assertEquals(GuardAction.OBSERVE, event.overallAction());
        Assert.assertEquals(1, event.records().size());
        Assert.assertEquals("test.audit", event.records().get(0).guardId());
    }
}
