package example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用本地 HTTP 协议桩验证真实客户端、SPI 与纯代码护栏链路，不消耗外部模型额度。
 * 协议桩仅存在于测试代码，业务入口始终连接配置的真实接口。
 */
final class GuardDemoTest {
    @TempDir
    Path temporary;

    @Test
    void shouldApplySpiGuardsAcrossActualHttpToolLoop() throws Exception {
        verifyToolLoop(false);
    }

    @Test
    void shouldApplyCodePolicyWithoutAnyYamlConfiguration() throws Exception {
        verifyToolLoop(true);
    }

    private void verifyToolLoop(boolean codePolicy) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> wireRequests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, mapper, wireRequests));
        server.start();
        String previous = System.getProperty("agentguard.agt.policy-path");
        try {
            LlmConfig config = config(Map.of(
                    "OPENAI_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "OPENAI_API_KEY", "local-test-key",
                    "OPENAI_MODEL_NAME", "protocol-test",
                    "AGENTGUARD_AGT_POLICY_PATH", codePolicy ? "" : "config/agt-policy.yaml"));
            if (codePolicy) {
                // 即使类路径里存在 AGT 的 SPI 工厂，显式引擎也不能触发其 YAML 初始化。
                String absentPolicy = temporary.resolve("must-not-exist.yaml").toString();
                System.setProperty("agentguard.agt.policy-path", absentPolicy);
                CodePolicyDemo.run(config);
                assertFalse(Files.exists(Path.of(absentPolicy)));
                assertEquals(absentPolicy, System.getProperty("agentguard.agt.policy-path"),
                        "纯代码入口不能改写或读取策略路径配置");
            } else {
                GuardDemo.run(config);
            }
            assertEquals(4, wireRequests.size(), "正常查询两轮 + 两个被拦截的模型工具调用");
            String wire = wireRequests.toString();
            assertFalse(wire.contains("DEMO_PRIVATE_INPUT"));
            assertFalse(wire.contains("DEMO_PRIVATE_TOOL"));
            assertTrue(wire.contains("MASKED_INPUT"));
            assertTrue(wire.contains("MASKED_TOOL"));
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
    void shouldReadUtf8QuotesAndEnvironmentOverrides() throws Exception {
        Path file = temporary.resolve(".env");
        Files.writeString(file, "# 配置示例\nOPENAI_MODEL_NAME='file-model'\nOPENAI_API_KEY=\"abc=def\"\n");
        LlmConfig config = LlmConfig.load(file, Map.of("OPENAI_MODEL_NAME", "env-model"));
        assertEquals("env-model", config.modelName());
        assertEquals("abc=def", config.apiKey());
    }

    @Test
    void shouldRejectPlaceholderAndInvalidTimeout() throws Exception {
        LlmConfig config = config(Map.of("OPENAI_API_KEY", "replace-with-your-api-key",
                "LLM_TIMEOUT_SECONDS", "0"));
        assertThrows(IllegalArgumentException.class, config::apiKey);
        assertThrows(IllegalArgumentException.class, config::timeout);
    }

    @Test
    void shouldSupportEnvironmentWithoutDotenv() throws Exception {
        LlmConfig config = config(Map.of("OPENAI_API_KEY", "local-test-key"));
        assertEquals("local-test-key", config.apiKey());
        assertEquals(60, config.timeout().toSeconds());
    }

    @Test
    void shouldRejectMalformedDotenvWithoutLeakingItsContent() throws Exception {
        Path file = temporary.resolve("bad.env");
        Files.writeString(file, "secret-without-equals");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LlmConfig.load(file, Map.of()));
        assertFalse(error.getMessage().contains("secret-without-equals"));
    }

    private LlmConfig config(Map<String, String> values) throws IOException {
        return LlmConfig.load(temporary.resolve("absent.env"), values);
    }

    private static void respond(HttpExchange exchange, ObjectMapper mapper, List<String> requests)
            throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(raw);
        JsonNode messages = mapper.readTree(raw).path("messages");
        JsonNode last = messages.get(messages.size() - 1);
        Map<String, Object> message;
        String finish;
        if ("tool".equals(last.path("role").asText())) {
            message = Map.of("role", "assistant", "content", "ORD-1001 已发货，内部标记 MASKED_TOOL。");
            finish = "stop";
        } else {
            String prompt = last.path("content").asText();
            String tenant = prompt.contains("tenant-B") ? "tenant-B" : "tenant-A";
            String order = prompt.contains("ORD-9999") ? "ORD-9999" : "ORD-1001";
            String arguments = mapper.writeValueAsString(Map.of("tenant", tenant, "orderId", order));
            message = Map.of("role", "assistant", "tool_calls", List.of(Map.of(
                    "id", "call-demo", "type", "function", "function",
                    Map.of("name", "lookup", "arguments", arguments))));
            finish = "tool_calls";
        }
        byte[] body = mapper.writeValueAsBytes(Map.of(
                "id", "chatcmpl-test", "object", "chat.completion", "created", 1,
                "model", "protocol-test", "choices",
                List.of(Map.of("index", 0, "message", message, "finish_reason", finish)),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
        exchange.close();
    }
}
