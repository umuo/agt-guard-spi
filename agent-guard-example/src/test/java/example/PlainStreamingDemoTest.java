package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 验证普通流式问答和工具循环，无需外部模型凭证。
 * 即使策略路径无效，默认不发现 SDK 也应正常完成实际 SSE 调用。
 */
class PlainStreamingDemoTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalChatStreamsBeforeCompletionWithoutInitializingSdk() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            begin(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                event(output, Map.of("role", "assistant", "content", "你好，"), null);
                try {
                    if (!received.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("普通流式片段应在服务端完成前转发");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
                event(output, Map.of("content", "这是普通流式回答。"), null);
                end(output, "stop");
            } finally {
                exchange.close();
            }
        });
        String previous = System.getProperty("agentguard.agt.policy-path");
        String invalidPath = "target/plain-demo-no-policy.yaml";
        System.setProperty("agentguard.agt.policy-path", invalidPath);
        try {
            StringBuilder streamed = new StringBuilder();
            PlainStreamingDemo.Result result = PlainStreamingDemo.run(config(server), "你好，请介绍一下自己", text -> {
                streamed.append(text);
                received.countDown();
            });
            assertEquals("你好，这是普通流式回答。", result.answer());
            assertEquals(result.answer(), streamed.toString());
            assertEquals(0, result.toolCalls());
            assertEquals(invalidPath, System.getProperty("agentguard.agt.policy-path"));
        } finally {
            server.stop(0);
            if (previous == null) {
                System.clearProperty("agentguard.agt.policy-path");
            } else {
                System.setProperty("agentguard.agt.policy-path", previous);
            }
        }
    }

    @Test
    void normalOrderQueryExecutesToolAndContinuesStreaming() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = server(exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(raw);
            JsonNode messages = mapper.readTree(raw).path("messages");
            boolean afterTool = "tool".equals(messages.get(messages.size() - 1).path("role").asText());
            begin(exchange);
            try (OutputStream output = exchange.getResponseBody()) {
                if (afterTool) {
                    event(output, Map.of("role", "assistant", "content", "ORD-1001 "), null);
                    event(output, Map.of("content", "已发货。"), null);
                    end(output, "stop");
                } else {
                    String arguments = mapper.writeValueAsString(Map.of("tenant", "tenant-A", "orderId", "ORD-1001"));
                    event(output, Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                            "index", 0, "id", "plain-call-1", "type", "function", "function",
                            Map.of("name", "lookup", "arguments", arguments)))), null);
                    end(output, "tool_calls");
                }
            } finally {
                exchange.close();
            }
        });
        try {
            StringBuilder streamed = new StringBuilder();
            PlainStreamingDemo.Result result = PlainStreamingDemo.run(config(server),
                    "请查询 tenant-A 的 ORD-1001", streamed::append);
            assertEquals("ORD-1001 已发货。", result.answer());
            assertEquals(result.answer(), streamed.toString());
            assertEquals(1, result.toolCalls());
            assertEquals(2, requests.size());
            JsonNode messages = mapper.readTree(requests.get(1)).path("messages");
            JsonNode tool = messages.get(messages.size() - 1);
            assertEquals("tool", tool.path("role").asText());
            assertTrue(tool.path("content").asText().contains("状态=已发货"));
        } finally {
            server.stop(0);
        }
    }

    private LlmConfig config(HttpServer server) throws IOException {
        return LlmConfig.load(Path.of("target/plain-demo-unconfigured.env"), Map.of(
                "OPENAI_API_KEY", "local-test-key", "OPENAI_MODEL_NAME", "plain-stream-test",
                "OPENAI_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "LLM_TIMEOUT_SECONDS", "5", "AGENTGUARD_AGT_POLICY_PATH", ""));
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.start();
        return server;
    }

    private void begin(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
    }

    private void end(OutputStream output, String reason) throws IOException {
        event(output, Map.of(), reason);
        output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private void event(OutputStream output, Map<String, Object> delta, String finish) throws IOException {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", finish);
        String chunk = mapper.writeValueAsString(Map.of("id", "chatcmpl-plain", "object", "chat.completion.chunk",
                "created", 1, "model", "plain-stream-test", "choices", List.of(choice)));
        output.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
}
