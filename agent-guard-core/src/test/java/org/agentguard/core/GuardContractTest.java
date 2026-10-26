package org.agentguard.core;

import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardAction;
import org.agentguard.decision.GuardDecision;
import org.agentguard.decision.GuardPolicy;
import org.agentguard.decision.RiskCategory;
import org.agentguard.decision.RiskLevel;
import org.agentguard.engine.GuardEngine;
import org.agentguard.engine.GuardOutcome;
import org.agentguard.event.GuardEvent;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.model.AgentInput;
import org.agentguard.spi.AgentInputGuard;
import org.agentguard.spi.Guard;
import org.agentguard.spi.GuardPolicyResolver;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * 验证框架适配器依赖的护栏边界契约，包括故障、改写、审计与请求状态。
 */
public class GuardContractTest {

    private final GuardContext context = GuardContext.builder().traceId("contract-test").build();
    private final Finding finding = Finding.of("DENIED", RiskCategory.PERMISSION, RiskLevel.HIGH);

    @Test
    public void emptyStageStillEmitsAuditEvent() {
        final List<GuardEvent> events = new ArrayList<GuardEvent>();
        GuardEngine engine = DefaultGuardEngine.builder().addListener(events::add).build();
        Assert.assertTrue(engine.checkAgentInput(AgentInput.of("safe"), context).isProceed());
        Assert.assertEquals(1, events.size());
        Assert.assertTrue(events.get(0).records().isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongPayloadEvenWithoutGuards() {
        DefaultGuardEngine.builder().build().check(GuardStage.AGENT_INPUT, "wrong", context);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullPayload() {
        DefaultGuardEngine.builder().build().checkAgentInput(null, context);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateIdsAcrossStages() {
        Guard<?> guard = guard("duplicate");
        new DefaultGuardEngine(Arrays.<Guard<?>>asList(guard, guard), null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankIds() {
        DefaultGuardEngine.builder().addGuard(guard(" ")).build();
    }

    @Test
    public void supportsFailureUsesResolvedFailOpenPolicy() {
        AgentInputGuard guard = new AgentInputGuard() {
            @Override
            public String id() {
                return "broken.supports";
            }

            @Override
            public boolean supports(GuardContext ctx) {
                throw new IllegalStateException("filter unavailable");
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput input, GuardContext ctx) {
                Assert.fail("must not run");
                return GuardDecision.pass();
            }
        };
        GuardOutcome<AgentInput> outcome = DefaultGuardEngine.builder().addGuard(guard)
                .policyResolver((g, ctx) -> GuardPolicy.of(
                        org.agentguard.decision.EnforcementMode.ENFORCE,
                        org.agentguard.decision.FailPolicy.FAIL_OPEN))
                .build().checkAgentInput(AgentInput.of("safe"), context);
        Assert.assertEquals(GuardAction.OBSERVE, outcome.action());
        Assert.assertNull(outcome.records().get(0).decidedAction());
        Assert.assertNotNull(outcome.records().get(0).error());
    }

    @Test
    public void resolverFailureIsAuditedAndClosedByDefault() {
        GuardPolicyResolver resolver = (guard, ctx) -> {
            throw new IllegalStateException("policy unavailable");
        };
        GuardOutcome<AgentInput> outcome = DefaultGuardEngine.builder().addGuard(guard("policy"))
                .policyResolver(resolver).build().checkAgentInput(AgentInput.of("safe"), context);
        Assert.assertTrue(outcome.isBlocked());
        try {
            outcome.payloadOrThrow();
            Assert.fail("must throw execution failure");
        } catch (GuardExecutionException expected) {
            Assert.assertEquals("policy", expected.guardId());
            Assert.assertEquals("policy unavailable", expected.getCause().getMessage());
        }
    }

    @Test(expected = CancellationException.class)
    public void cancellationIsNeverConvertedToFailOpen() {
        AgentInputGuard guard = new AgentInputGuard() {
            @Override
            public String id() {
                return "cancel";
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput input, GuardContext ctx) {
                throw new CancellationException();
            }
        };
        DefaultGuardEngine.builder().addGuard(guard).policyResolver((g, ctx) -> GuardPolicy.OBSERVE_ONLY)
                .build().checkAgentInput(AgentInput.of("safe"), context);
    }

    @Test(expected = AssertionError.class)
    public void jvmErrorsAreNotSwallowed() {
        AgentInputGuard guard = new AgentInputGuard() {
            @Override
            public String id() {
                return "fatal";
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput input, GuardContext ctx) {
                throw new AssertionError("fatal");
            }
        };
        DefaultGuardEngine.builder().addGuard(guard).build().checkAgentInput(AgentInput.of("safe"), context);
    }

    @Test
    public void explicitDenialIsNotDowngradedByFailOpen() {
        AgentInputGuard guard = new AgentInputGuard() {
            @Override
            public String id() {
                return "explicit.block";
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput input, GuardContext ctx) {
                throw new GuardBlockedException(ctx.stage(), "denied", Arrays.asList(finding));
            }
        };
        GuardOutcome<AgentInput> outcome = DefaultGuardEngine.builder().addGuard(guard)
                .policyResolver((g, ctx) -> GuardPolicy.of(
                        org.agentguard.decision.EnforcementMode.ENFORCE,
                        org.agentguard.decision.FailPolicy.FAIL_OPEN))
                .build().checkAgentInput(AgentInput.of("safe"), context);
        Assert.assertTrue(outcome.isBlocked());
        Assert.assertNull(outcome.records().get(0).error());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void wrongRewriteTypeIsAnExecutionFailure() {
        AgentInputGuard guard = new AgentInputGuard() {
            @Override
            public String id() {
                return "invalid.rewrite";
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput input, GuardContext ctx) {
                return (GuardDecision) GuardDecision.modify("wrong type", finding);
            }
        };
        GuardOutcome<AgentInput> outcome = DefaultGuardEngine.builder().addGuard(guard).build()
                .checkAgentInput(AgentInput.of("safe"), context);
        Assert.assertTrue(outcome.isBlocked());
        Assert.assertNull(outcome.records().get(0).decidedAction());
    }

    @Test
    public void derivedContextsShareOnlyTheirOwnRequestState() {
        GuardContext derived = context.withStage(GuardStage.MODEL_INPUT).withIteration(2);
        context.state().put("risk", "high");
        Assert.assertEquals("high", derived.state().get("risk"));
        Assert.assertTrue(GuardContext.builder().build().state().isEmpty());
    }

    private AgentInputGuard guard(final String id) {
        return new AgentInputGuard() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public GuardDecision<AgentInput> check(AgentInput input, GuardContext ctx) {
                return GuardDecision.pass();
            }
        };
    }
}
