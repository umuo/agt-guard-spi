package example;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.context.StandardAttributes;
import org.agentguard.engine.GuardEngine;
import org.agentguard.exception.GuardBlockedException;

/**
 * 使用真实 OpenAI 兼容模型演示 SDK 的 SPI 自动装配与六阶段生命周期。
 * 业务工具仅返回固定测试订单，计数用于验证被拒绝的调用没有执行工具。
 */
public final class GuardDemo {
    private GuardDemo() {
    }

    public static void main(String[] args) throws Exception {
        Path dotenv = Path.of(args.length == 0 ? ".env" : args[0]);
        run(LlmConfig.load(dotenv));
    }

    public static void run(LlmConfig config) throws Exception {
        // 在构建 AiService 前完成配置，工厂仍由 LangChain4j 自行发现。
        config.apiKey();
        if (!Files.isRegularFile(config.policyPath())) {
            throw new IllegalArgumentException("策略文件不存在，请检查 AGENTGUARD_AGT_POLICY_PATH");
        }
        System.setProperty("agentguard.agt.policy-path", config.policyPath().toString());
        runScenarios(config, null, "SPI 自动装配演示");
    }

    static void runScenarios(LlmConfig config, GuardEngine engine, String mode) throws Exception {
        String apiKey = config.apiKey();
        RequestProbe probe = new RequestProbe();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(apiKey)
                .modelName(config.modelName())
                .timeout(config.timeout())
                .maxRetries(0)
                .listeners(probe)
                .logRequests(false)
                .logResponses(false)
                .build();
        OrderTools tools = new OrderTools();
        Assistant reader = assistant(model, tools, Set.of("orders:read"), engine);
        System.out.println(mode + "开始；模型=" + config.modelName());

        // 先验证拒绝行为；缺少 SDK 或自动发现未生效时，必须立即失败。
        expectBlocked(reader, "DENIED", GuardStage.AGENT_INPUT, null);
        check(probe.requests.isEmpty(), "输入阻断后不应调用模型");
        check(tools.calls.get() == 0, "输入阻断后不应执行工具");
        System.out.println("[PASS] 输入内容拦截：模型请求 0，工具执行 0");

        String answer = reader.chat("请查询 tenant-A 的 ORD-1001，备注 DEMO_PRIVATE_INPUT。");
        check(answer != null && !answer.isBlank(), "真实模型必须返回非空答案");
        check(tools.calls.get() == 1, "正常场景必须真正执行一次 lookup");
        check(probe.requests.size() >= 2, "工具执行后必须再次调用真实模型");
        String sent = probe.requests.toString();
        check(!sent.contains("DEMO_PRIVATE_INPUT"), "输入敏感标记不能发送给模型");
        check(sent.contains("MASKED_INPUT"), "输入改写必须实际进入模型请求");
        check(!sent.contains("DEMO_PRIVATE_TOOL"), "工具敏感标记不能发送给模型");
        check(sent.contains("MASKED_TOOL"), "工具结果改写必须实际进入下一轮模型请求");
        check(!answer.contains("DEMO_PRIVATE_TOOL"), "最终答案不能包含工具原始敏感标记");
        System.out.println("[PASS] 正常查询 + 输入改写 + 工具结果脱敏；回答：" + answer);

        int beforeModel = probe.requests.size();
        int beforeTool = tools.calls.get();
        expectBlocked(reader, "请查询 tenant-B 的 ORD-9999。", GuardStage.TOOL_INPUT,
                "context_binding_denied");
        check(probe.requests.size() > beforeModel, "跨租户场景必须由真实模型生成工具调用");
        check(tools.calls.get() == beforeTool, "跨租户工具必须在实际执行前阻断");
        System.out.println("[PASS] 跨租户阻断：TOOL_INPUT，工具执行增量 0");

        Assistant noPermission = assistant(model, tools, Set.of(), engine);
        beforeModel = probe.requests.size();
        expectBlocked(noPermission, "请查询 tenant-A 的 ORD-1001。", GuardStage.TOOL_INPUT,
                "permission_denied");
        check(probe.requests.size() > beforeModel, "缺少权限场景必须由真实模型生成工具调用");
        check(tools.calls.get() == beforeTool, "缺少权限的工具不能执行");
        System.out.println("[PASS] 权限阻断：TOOL_INPUT，工具执行增量 0");
        System.out.println("全部场景通过；模型请求=" + probe.requests.size() + "，工具执行=" + tools.calls.get());
    }

    private static Assistant assistant(
            OpenAiChatModel model, OrderTools tools, Set<String> permissions, GuardEngine engine) {
        AiServices<Assistant> builder = AiServices.builder(Assistant.class)
                .chatModel(model)
                .tools(tools)
                .maxToolCallingRoundTrips(2)
                .systemMessage("你是订单工具调用演示助手。对于每个订单查询，必须先调用一次 lookup，"
                        + "tenant 和 orderId 严格使用用户给定的值，不自行更换租户或判断权限；"
                        + "授权由服务端执行。拿到工具结果后直接用中文回答，不再次调用工具，不编造数据。")
                .guardContextFactory(invocation -> GuardContext.builder()
                        .traceId(UUID.randomUUID().toString())
                        .agentId("order-demo")
                        .tenantId("tenant-A")
                        .userId("demo-user")
                        .attributes(Map.of(StandardAttributes.PERMISSIONS, permissions))
                        .build())
                // YAML 案例显式开启 SPI；代码案例传显式引擎，跳过发现。
                .guardAutoDiscovery(engine == null)
                .guardEngine(engine);
        Assistant assistant = builder.build();
        System.out.println("护栏配置诊断：" + builder.guardDiagnostics());
        return assistant;
    }

    private static void expectBlocked(Assistant assistant, String prompt, GuardStage stage, String code) {
        try {
            assistant.chat(prompt);
        } catch (GuardBlockedException blocked) {
            check(blocked.stage() == stage, "阻断阶段应为 " + stage + "，实际=" + blocked.stage());
            check(!blocked.findings().isEmpty(), "违规阻断必须提供风险发现");
            if (code != null) {
                check(blocked.findings().stream().anyMatch(finding -> code.equals(finding.code())),
                        "阻断原因应为 " + code);
            }
            return;
        }
        throw new IllegalStateException("预期护栏阻断但请求完成：" + stage);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 普通非流式 AI Service 接口，每次调用使用独立上下文且不保留聊天记忆。
     */
    public interface Assistant {
        String chat(String message);
    }

    /**
     * 固定订单演示工具，记录真正进入方法体的次数，不访问实际业务数据。
     */
    public static final class OrderTools {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool("查询指定租户的订单状态，必须提供 tenant 和 orderId")
        public String lookup(
                @P(value = "用户指定的租户标识，例如 tenant-A", name = "tenant") String tenant,
                @P(value = "用户指定的订单号，例如 ORD-1001", name = "orderId") String orderId) {
            calls.incrementAndGet();
            return "订单=" + orderId + "，租户=" + tenant + "，状态=已发货，内部标记=DEMO_PRIVATE_TOOL";
        }
    }

    /**
     * 在实际 OpenAI 客户端接收请求时记录内存证据，不打印请求或认证信息。
     */
    private static final class RequestProbe implements ChatModelListener {
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public void onRequest(ChatModelRequestContext context) {
            requests.add(context.chatRequest());
        }
    }
}
