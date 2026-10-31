package example;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 集中读取 LLM 与策略配置，系统环境变量覆盖 dotenv 文件。
 * 仅支持 KEY=value 与成对引号，不执行 shell 或变量展开；不输出密钥。
 */
public final class LlmConfig {
    private final Map<String, String> values;

    private LlmConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static LlmConfig load(Path path) throws IOException {
        return load(path, System.getenv());
    }

    static LlmConfig load(Path path, Map<String, String> environment) throws IOException {
        Map<String, String> values = new HashMap<>();
        if (Files.exists(path)) {
            int number = 0;
            for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                number++;
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator < 1) {
                    throw new IllegalArgumentException("dotenv 第 " + number + " 行必须使用 KEY=value");
                }
                String key = line.substring(0, separator).strip();
                String value = line.substring(separator + 1).strip();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    if (value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }
                }
                values.put(key, value);
            }
        }
        values.putAll(environment);
        return new LlmConfig(values);
    }

    public String baseUrl() {
        String value = required("OPENAI_BASE_URL");
        URI uri = URI.create(value);
        if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("OPENAI_BASE_URL 必须为不包含凭证的 HTTP(S) URL");
        }
        return value;
    }

    public String apiKey() {
        return required("OPENAI_API_KEY");
    }

    public String modelName() {
        return required("OPENAI_MODEL_NAME");
    }

    public Duration timeout() {
        long seconds = Long.parseLong(values.getOrDefault("LLM_TIMEOUT_SECONDS", "60"));
        if (seconds < 1 || seconds > 600) {
            throw new IllegalArgumentException("LLM_TIMEOUT_SECONDS 必须在 1–600 之间");
        }
        return Duration.ofSeconds(seconds);
    }

    public Path policyPath() {
        String value = values.getOrDefault("AGENTGUARD_AGT_POLICY_PATH", "config/agt-policy.yaml");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AGENTGUARD_AGT_POLICY_PATH 不能为空");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private String required(String key) {
        String value = values.get(key);
        if (value == null || value.isBlank() || value.contains("replace-with")) {
            throw new IllegalArgumentException("请在 .env 或环境变量中配置 " + key);
        }
        return value;
    }
}
