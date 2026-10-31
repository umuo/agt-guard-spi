package example;

import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import org.agentguard.agt.AgtGuards;
import org.agentguard.engine.GuardEngine;

/**
 * 使用便捷 Builder 创建纯代码策略，不读取 YAML 或发现 SDK 工厂。
 * 复用真实模型配置和订单验证场景，展示业务侧的最小护栏配置。
 */
public final class CodePolicyDemo {
    private static final Consumer<AgtGuards.Builder> COMMON_RULES = guard -> guard
            .denyText("DENIED")
            .maskInput(Map.of("DEMO_PRIVATE_INPUT", "MASKED_INPUT"))
            .maskToolOutput(Map.of("DEMO_PRIVATE_TOOL", "MASKED_TOOL"));

    private CodePolicyDemo() {
    }

    public static void main(String[] args) throws Exception {
        Path dotenv = Path.of(args.length == 0 ? ".env" : args[0]);
        run(LlmConfig.load(dotenv));
    }

    public static void run(LlmConfig config) throws Exception {
        GuardDemo.runScenarios(config, createEngine(), "纯代码策略演示");
    }

    public static GuardEngine createEngine() {
        return configuration().build();
    }

    public static StructuredPolicy createPolicy() {
        return configuration().buildPolicy();
    }

    private static AgtGuards.Builder configuration() {
        return AgtGuards.builder()
                .version("code-demo-v1")
                .configure(COMMON_RULES)
                .tenantTool("lookup", "orders:read", "tenant", tool -> tool.requireStrings("orderId"));
    }
}
