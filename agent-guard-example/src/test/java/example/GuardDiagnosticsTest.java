package example;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.IllegalConfigurationException;
import dev.langchain4j.service.guard.AgentGuardDiagnostics;
import org.agentguard.diagnostics.GuardDiagnostics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 使用实际 SDK classpath 验证缺策略和纯代码策略的诊断，不发送外部模型请求。
 * 显式引擎无需 YAML，自动配置缺少策略仍然在构建阶段失败。
 */
final class GuardDiagnosticsTest {
    @Test
    void codePolicyReportsManualSourceWithoutReadingInvalidYaml() {
        String previous = System.getProperty("agentguard.agt.policy-path");
        try {
            System.setProperty("agentguard.agt.policy-path", "private-missing-policy.yaml");
            AiServices<GuardDemo.Assistant> builder = builder().guardEngine(CodePolicyDemo.createEngine());
            builder.build();
            AgentGuardDiagnostics report = builder.guardDiagnostics();
            assertEquals(AgentGuardDiagnostics.Status.CONFIGURED, report.status());
            assertEquals(AgentGuardDiagnostics.Source.MANUAL, report.source());
            assertEquals(GuardDiagnostics.PolicySource.PROGRAMMATIC, report.engine().providers().get(0).policySource());
            assertFalse(report.toString().contains("private-missing-policy"));
        } finally {
            restore(previous);
        }
    }

    @Test
    void missingAutomaticPolicyFailsAndLeavesSafeDiagnostics() {
        String previous = System.getProperty("agentguard.agt.policy-path");
        try {
            System.setProperty("agentguard.agt.policy-path", "");
            AiServices<GuardDemo.Assistant> builder = builder().guardAutoDiscovery(true);
            assertThrows(IllegalConfigurationException.class, builder::build);
            assertEquals(AgentGuardDiagnostics.Status.FAILED, builder.guardDiagnostics().status());
            assertEquals(AgentGuardDiagnostics.Problem.SDK_INITIALIZATION, builder.guardDiagnostics().problem());
            assertEquals(AgentGuardDiagnostics.Source.SPI, builder.guardDiagnostics().source());
        } finally {
            restore(previous);
        }
    }

    private static AiServices<GuardDemo.Assistant> builder() {
        return AiServices.builder(GuardDemo.Assistant.class).chatModel(OpenAiChatModel.builder()
                .apiKey("local-test-key").modelName("protocol-test").build());
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty("agentguard.agt.policy-path");
        } else {
            System.setProperty("agentguard.agt.policy-path", previous);
        }
    }
}
