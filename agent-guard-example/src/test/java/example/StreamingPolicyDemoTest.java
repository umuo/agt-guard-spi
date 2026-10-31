package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.streaming.GuardStreamOptions;
import org.junit.jupiter.api.Test;

/**
 * 用实际 OpenAI SSE 客户端连接本地协议服务，验证窗口与完整缓存的端到端行为。
 * 服务模拟分片工具参数及跨片段拒绝字符串，不调用外部模型。
 */
class StreamingPolicyDemoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void windowStreamsToolLoopAndNeverSendsOriginalSensitiveMarkers() throws Exception {
        List<String> requests = new ArrayList<>();
        CountDownLatch firstRelease = new CountDownLatch(1);
        HttpServer server = server(exchange -> normal(exchange, requests, firstRelease));
        try {
            StringBuilder output = new StringBuilder();
            AtomicInteger batches = new AtomicInteger();
            String answer = StreamingPolicyDemo.run(config(server), GuardStreamOptions.window(2, 50, 128),
                    "请查询 tenant-A 的 ORD-1001，备注 DEMO_PRIVATE_INPUT。", text -> {
                        batches.incrementAndGet();
                        output.append(text);
                        firstRelease.countDown();
                    });
            assertEquals("ORD-1001 已发货。MASKED_TOOL", answer);
            assertEquals(answer, output.toString());
            assertEquals(2, requests.size());
            assertTrue(batches.get() > 1);
            assertFalse(requests.toString().contains("DEMO_PRIVATE_INPUT"));
            assertFalse(requests.toString().contains("DEMO_PRIVATE_TOOL"));
            assertTrue(requests.toString().contains("MASKED_TOOL"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void windowBlocksCrossChunkWordAndStrictReleasesNothingOnFinalBlock() throws Exception {
        for (GuardStreamOptions options : List.of(GuardStreamOptions.window(1, 50, 128), GuardStreamOptions.strict())) {
            HttpServer server = server(exchange -> blocked(exchange));
            try {
                StringBuilder output = new StringBuilder();
                ExecutionException error = assertThrows(ExecutionException.class,
                        () -> StreamingPolicyDemo.run(config(server), options, "请输出演示文本", output::append));
                assertInstanceOf(GuardBlockedException.class, error.getCause());
                assertFalse(output.toString().contains("DENIED"));
                assertFalse(output.toString().contains("DE"));
                if (options.mode() == GuardStreamOptions.Mode.STRICT) {
                    assertEquals("", output.toString());
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void strictToolLoopEmitsOnlyCheckedFinalText() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = server(exchange -> normal(exchange, requests));
        try {
            StringBuilder output = new StringBuilder();
            String answer = StreamingPolicyDemo.run(config(server), GuardStreamOptions.strict(),
                    "请查询 tenant-A 的 ORD-1001", output::append);
            assertEquals("ORD-1001 已发货。MASKED_TOOL", answer);
            assertEquals(answer, output.toString());
            assertEquals(2, requests.size());
        } finally {
            server.stop(0);
        }
    }

    private LlmConfig config(HttpServer server) throws IOException {
        return LlmConfig.load(Path.of("target/unused-stream.env"), Map.of(
                "OPENAI_API_KEY", "local-test-key", "OPENAI_MODEL_NAME", "stream-test",
                "OPENAI_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "LLM_TIMEOUT_SECONDS", "5", "AGENTGUARD_AGT_POLICY_PATH", ""));
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.start();
        return server;
    }

    private void normal(HttpExchange exchange, List<String> requests) throws IOException {
        normal(exchange, requests, null);
    }

    private void normal(HttpExchange exchange, List<String> requests, CountDownLatch firstRelease) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(raw);
        JsonNode messages = mapper.readTree(raw).path("messages");
        boolean toolResult = "tool".equals(messages.get(messages.size() - 1).path("role").asText());
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            if (!toolResult) {
                event(output, Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                        "index", 0, "id", "call-1", "type", "function", "function",
                        Map.of("name", "lookup", "arguments", "{\"tenant\":\"tenant-A\",")))), null);
                event(output, Map.of("tool_calls", List.of(Map.of("index", 0, "function",
                        Map.of("arguments", "\"orderId\":\"ORD-1001\"}")))), null);
                event(output, Map.of(), "tool_calls");
            } else {
                event(output, Map.of("role", "assistant", "content", "ORD-1001 "), null);
                if (firstRelease != null) {
                    try {
                        if (!firstRelease.await(3, TimeUnit.SECONDS)) {
                            throw new IOException("窗口模式在服务端完成之前没有释放前缀");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
                event(output, Map.of("content", "已发货。"), null);
                event(output, Map.of("content", "MASKED_TOOL"), null);
                event(output, Map.of(), "stop");
            }
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } finally {
            exchange.close();
        }
    }

    private void blocked(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            event(output, Map.of("role", "assistant", "content", "safe prefix "), null);
            event(output, Map.of("content", "DE"), null);
            event(output, Map.of("content", "NIED"), null);
            event(output, Map.of(), "stop");
            output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException cancelled) {
            // 违规后客户端主动取消连接，服务端可能看到管道关闭。
        } finally {
            exchange.close();
        }
    }

    private void event(OutputStream output, Map<String, Object> delta, String finish) throws IOException {
        Map<String, Object> choice = new java.util.LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finish);
        String data = mapper.writeValueAsString(Map.of("id", "chatcmpl-stream-test", "object", "chat.completion.chunk",
                "created", 1, "model", "stream-test", "choices", List.of(choice)));
        output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
