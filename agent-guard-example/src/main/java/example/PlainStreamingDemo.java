package example;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 普通 LangChain4j 流式与工具调用示例，不创建任何安全策略或护栏引擎。
 * 使用默认关闭的 SDK 自动发现，普通调用无需额外护栏配置。
 */
public final class PlainStreamingDemo {
    private PlainStreamingDemo() {
    }

    /**
     * 普通流式业务接口，直接使用模型返回的增量文本。
     */
    public interface Assistant {
        TokenStream chat(String prompt);
    }

    /**
     * 使用固定测试数据的普通订单工具，不接入租户授权或脱敏规则。
     */
    public static final class OrderTools {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool("查询指定租户下的订单状态")
        public String lookup(
                @P(value = "用户指定的租户，例如 tenant-A", name = "tenant") String tenant,
                @P(value = "用户指定的订单号，例如 ORD-1001", name = "orderId") String orderId) {
            calls.incrementAndGet();
            return "订单=" + orderId + "，租户=" + tenant + "，状态=已发货";
        }
    }

    /**
     * 返回最终轮次的文本与实际工具执行次数，便于观察普通调用流程。
     */
    public record Result(String answer, int toolCalls) {
    }

    public static void main(String[] args) throws Exception {
        Path dotenv = Path.of(args.length == 0 ? ".env" : args[0]);
        String prompt = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "请查询 tenant-A 的 ORD-1001。";
        LlmConfig config = LlmConfig.load(dotenv);
        System.out.println("普通流式案例，模型=" + config.modelName());
        Result result = run(config, prompt, System.out::print);
        System.out.println("\n调用完成，工具执行次数=" + result.toolCalls());
    }

    public static Result run(LlmConfig config, String prompt, Consumer<String> output) throws Exception {
        OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                .apiKey(config.apiKey())
                .baseUrl(config.baseUrl())
                .modelName(config.modelName())
                .timeout(config.timeout())
                .logRequests(false)
                .logResponses(false)
                .build();
        OrderTools tools = new OrderTools();
        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatModel(model)
                .tools(tools)
                .maxToolCallingRoundTrips(2)
                .systemMessage("你是普通问答和订单查询助手。日常问答直接回答，不调用工具；"
                        + "查询订单时先调用 lookup，使用用户给出的 tenant 和 orderId。"
                        + "拿到工具结果后直接回答，不重复调用工具。")
                .build();
        CompletableFuture<String> completed = new CompletableFuture<>();
        StringBuilder finalRound = new StringBuilder();
        assistant.chat(prompt)
                .onPartialResponse(text -> {
                    finalRound.append(text);
                    output.accept(text);
                })
                .onIntermediateResponse(response -> finalRound.setLength(0))
                .onCompleteResponse(response -> completed.complete(response.aiMessage().text()))
                .onError(completed::completeExceptionally)
                .start();
        String answer = completed.get(config.timeout().toSeconds() * 3, TimeUnit.SECONDS);
        if (answer == null || answer.isBlank() || !answer.equals(finalRound.toString())) {
            throw new IllegalStateException("模型未返回有效文本，或最终轮次增量文本与完整结果不一致");
        }
        return new Result(answer, tools.calls.get());
    }
}
