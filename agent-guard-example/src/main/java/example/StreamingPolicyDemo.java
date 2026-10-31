package example;

import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.agentguard.context.GuardContext;
import org.agentguard.context.StandardAttributes;
import org.agentguard.streaming.GuardStreamOptions;

/**
 * 使用纯代码策略和真实流式模型演示完整缓存或滑动窗口输出。
 * 不读取 YAML，业务工具和模型配置沿用订单案例。
 */
public final class StreamingPolicyDemo {
    private StreamingPolicyDemo() {
    }

    /**
     * 供流式模型组装代理的业务接口。
     */
    public interface Assistant {
        TokenStream chat(String prompt);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "window" : args[0];
        if (!"window".equals(mode) && !"strict".equals(mode)) {
            throw new IllegalArgumentException("第一个参数应为 window 或 strict");
        }
        Path dotenv = Path.of(args.length > 1 ? args[1] : ".env");
        LlmConfig config = LlmConfig.load(dotenv);
        GuardStreamOptions options = "strict".equals(mode) ? GuardStreamOptions.strict() : GuardStreamOptions.window();
        System.out.println("流式护栏模式=" + mode + "，模型=" + config.modelName());
        String answer = run(config, options, "请查询 tenant-A 的 ORD-1001，备注 DEMO_PRIVATE_INPUT。", System.out::print);
        System.out.println("\n[PASS] 流式查询完成，最终文本与已释放片段一致，回答长度=" + answer.length());
    }

    public static String run(LlmConfig config, GuardStreamOptions options, String prompt, Consumer<String> output)
            throws Exception {
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .apiKey(config.apiKey()).baseUrl(config.baseUrl()).modelName(config.modelName())
                .timeout(config.timeout()).logRequests(false).logResponses(false).build();
        AiServices<Assistant> builder = AiServices.builder(Assistant.class)
                .streamingChatModel(model)
                .tools(new GuardDemo.OrderTools())
                .systemMessage("你是订单查询助手。查询订单时必须先调用一次 lookup，严格使用用户给定的 tenant 和 orderId；"
                        + "权限由宿主校验。拿到工具结果后直接用中文回答，不重复调用工具。")
                .guardEngine(CodePolicyDemo.createEngine())
                .guardStreaming(options)
                .guardContextFactory(invocation -> GuardContext.builder()
                        .traceId(UUID.randomUUID().toString()).agentId("stream-demo")
                        .tenantId("tenant-A").userId("demo-user")
                        .attributes(Map.of(StandardAttributes.PERMISSIONS, Set.of("orders:read"))).build());
        Assistant assistant = builder.build();
        System.out.println("护栏配置诊断：" + builder.guardDiagnostics());
        CompletableFuture<String> result = new CompletableFuture<>();
        StringBuilder released = new StringBuilder();
        assistant.chat(prompt)
                .onPartialResponse(text -> {
                    released.append(text);
                    output.accept(text);
                })
                .onIntermediateResponse(response -> released.setLength(0))
                .onCompleteResponse(response -> result.complete(response.aiMessage().text()))
                .onError(result::completeExceptionally)
                .start();
        String answer = result.get(config.timeout().toSeconds() * 3, TimeUnit.SECONDS);
        if (!released.toString().equals(answer) || answer == null || answer.isBlank()) {
            throw new IllegalStateException("实际释放的文本与最终回答不一致，或模型未返回文本");
        }
        return answer;
    }
}
