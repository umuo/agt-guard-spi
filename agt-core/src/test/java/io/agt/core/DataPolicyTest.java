package io.agt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agt.core.data.DataLimits;
import io.agt.core.data.DataOperation;
import io.agt.core.data.DataResource;
import io.agt.core.data.DataRule;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.StructuredPolicy;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 数据访问治理策略的单元测试。
 *
 * <p>验证基于 YAML 与编程式构建的数据资源规则、操作权限、内容改写及仅评估模式。
 */
class DataPolicyTest {
    static final TrustedContext CTX =
            new TrustedContext("agent", "tenant-a", "alice", Java8.set("read"));
    static final DataResource SOURCE =
            new DataResource("knowledge", DataResource.Kind.RETRIEVAL, "tenant-a", null, "index-a");
    static final String YAML =
            "apiVersion: agt.java/v1\nversion: data-test\ninterventionPoints: [agent_startup, input, pre_model_call, post_model_call, pre_tool_call, post_tool_call, output, agent_shutdown, pre_data_access, post_data_access]\nbudget: {maxModelCalls: 20, maxToolCalls: 20, maxDurationMillis: 300000}\ndataLimits: {maxCalls: 1, maxItems: 10}\ntools: {}\nresources:\n  knowledge:\n    kind: RETRIEVAL\n    scope: TENANT\n    permissions:\n      RETRIEVE: [read]\ncontentRules:\n  - id: rewrite\n    points: [pre_data_access]\n    denyContains: []\n    replacements: {old: new}\n";

    @Test
    void yamlAndProgrammaticPoliciesUseSameDecisionAndEffectiveValue() {
        StructuredPolicy yaml = StructuredPolicy.fromYaml(YAML);
        StructuredPolicy java =
                StructuredPolicy.builder("data-test")
                        .dataLimits(new DataLimits(1, 10))
                        .resource(
                                "knowledge",
                                new DataRule(
                                        DataResource.Kind.RETRIEVAL,
                                        DataRule.Scope.TENANT,
                                        Java8.map(DataOperation.RETRIEVE, Java8.set("read"))))
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Java8.set(InterventionPoint.PRE_DATA_ACCESS),
                                        Java8.list(),
                                        Java8.map("old", "new")))
                        .build();
        PolicyRequest request =
                new PolicyRequest(
                        InterventionPoint.PRE_DATA_ACCESS,
                        CTX,
                        "session",
                        "action",
                        null,
                        SOURCE.target(DataOperation.RETRIEVE, "old"));
        assertEquals(java.evaluate(request), yaml.evaluate(request));
        assertEquals(
                "new", SOURCE.value(yaml.evaluate(request).replacement(), DataOperation.RETRIEVE));
    }

    @Test
    void invalidResourcesAndUnknownActionsFailAtLoad() {
        for (String invalid :
                Java8.list(
                        YAML.replace("RETRIEVE: [read]", "DELETE_ALL: [read]"),
                        YAML.replace("RETRIEVE: [read]", "MEMORY_READ: [read]"),
                        YAML.replace("TENANT", "UNKNOWN"),
                        YAML.replace("RETRIEVE: [read]", "RETRIEVE: []"),
                        YAML.replace("maxCalls: 1", "maxCalls: 0"),
                        YAML.replace(
                                "scope: TENANT", "scope: TENANT\n    expression: 'permitAll()'"),
                        YAML.replace(", pre_data_access, post_data_access", ""))) {
            assertEquals(
                    "invalid_policy",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> StructuredPolicy.fromYaml(invalid))
                            .code());
        }
    }

    @Test
    void absentResourceOrOperationDeniedWithZeroEffects() {
        StructuredPolicy p =
                StructuredPolicy.builder("deny")
                        .points(EnumSet.allOf(InterventionPoint.class))
                        .build();
        try (AgentControl control = AgentControl.builder(p).audit(r -> {}).build();
                HostSession session = control.openSession(CTX)) {
            AtomicInteger effects = new AtomicInteger();
            assertEquals(
                    "data_resource_denied",
                    assertThrows(
                                    GovernanceException.class,
                                    () ->
                                            session.dataOperationPrepared(
                                                    SOURCE,
                                                    DataOperation.RETRIEVE,
                                                    "q",
                                                    v -> v,
                                                    v -> {
                                                        effects.incrementAndGet();
                                                        return "x";
                                                    },
                                                    v -> v,
                                                    (v, e) -> (String) e))
                            .code());
            assertEquals(0, effects.get());
            assertEquals(0, session.budgetSnapshot().dataCalls());
        }
    }

    @Test
    void failedActualDataIoStillCountsAndAsyncDoesNotDoubleCount() throws Exception {
        StructuredPolicy p = StructuredPolicy.fromYaml(YAML);
        try (AgentControl control = AgentControl.builder(p).audit(r -> {}).build();
                HostSession session = control.openSession(CTX)) {
            CompletableFuture<Object> result =
                    session.dataOperationAsyncPrepared(
                            SOURCE,
                            DataOperation.RETRIEVE,
                            "q",
                            v -> v,
                            v -> Java8.failedFuture(new GovernanceException("storage_unavailable")),
                            v -> v,
                            (v, e) -> e);
            assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertEquals(1, session.budgetSnapshot().dataCalls());
            assertEquals(0, session.budgetSnapshot().modelCalls());
            assertEquals(0, session.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void observingModeRecordsWithoutRewritingOrBlocking() {
        TrustedContext identity = new TrustedContext("agent", "tenant-b", "bob", Java8.set());
        try (AgentControl control =
                        AgentControl.builder(StructuredPolicy.fromYaml(YAML))
                                .mode(EnforcementMode.EVALUATE_ONLY)
                                .audit(r -> {})
                                .build();
                HostSession session = control.openSession(identity)) {
            String result =
                    session.dataOperationPrepared(
                            SOURCE,
                            DataOperation.RETRIEVE,
                            "old",
                            v -> (String) v,
                            v -> v,
                            v -> v,
                            (v, e) -> (String) e);
            assertEquals("old", result);
            assertEquals(1, session.budgetSnapshot().dataCalls());
        }
    }
}
