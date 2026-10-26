package org.agentguard.agt;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.Java8;
import io.agt.core.JsonValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.agentguard.GuardStage;
import org.agentguard.model.AgentInput;
import org.agentguard.model.AgentOutput;
import org.agentguard.model.ContentPart;
import org.agentguard.model.GuardMessage;
import org.agentguard.model.ModelRequest;
import org.agentguard.model.ModelResponse;
import org.agentguard.model.ToolCall;
import org.agentguard.model.ToolDefinition;
import org.agentguard.model.ToolResult;

/**
 * 将六阶段数据显式投影为有界 JSON，并在改写后恢复框架中立类型。
 * 身份和结构不能被内容替换间接改变，媒体和任意 POJO 明确拒绝。
 */
final class AgtPayloads {
    private AgtPayloads() {}

    /**
     * 保存策略目标及原类型恢复函数，工具名独立于可改写参数。
     */
    static final class Projection {
        private final Object target;
        private final String toolName;
        private final Function<Object, Object> restore;

        @JsonCreator
        public Projection(
                @JsonProperty("target") Object target,
                @JsonProperty("toolName") String toolName,
                @JsonProperty("restore") Function<Object, Object> restore) {

            this.target = target;
            this.toolName = toolName;
            this.restore = restore;
        }

        @JsonProperty("target")
        public Object target() {
            return target;
        }

        @JsonProperty("toolName")
        public String toolName() {
            return toolName;
        }

        @JsonProperty("restore")
        public Function<Object, Object> restore() {
            return restore;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (value == null || getClass() != value.getClass()) {
                return false;
            }
            Projection other = (Projection) value;
            return Objects.equals(target, other.target)
                    && Objects.equals(toolName, other.toolName)
                    && Objects.equals(restore, other.restore);
        }

        @Override
        public int hashCode() {
            int result = 0;
            result = 31 * result + Objects.hashCode(target);
            result = 31 * result + Objects.hashCode(toolName);
            result = 31 * result + Objects.hashCode(restore);
            return result;
        }

        @Override
        public String toString() {
            return "Projection[target="
                    + target
                    + ", toolName="
                    + toolName
                    + ", restore="
                    + restore
                    + "]";
        }
    }

    static Projection project(GuardStage stage, Object payload) {
        switch (stage) {
            case AGENT_INPUT:
                return input((AgentInput) payload);
            case MODEL_INPUT:
                return modelInput((ModelRequest) payload);
            case MODEL_OUTPUT:
                return modelOutput((ModelResponse) payload);
            case TOOL_INPUT:
                return toolInput((ToolCall) payload);
            case TOOL_OUTPUT:
                return toolOutput((ToolResult) payload);
            case AGENT_OUTPUT:
                return output((AgentOutput) payload);
            default:
                throw new IllegalStateException("Unknown enum value");
        }
    }

    private static Projection input(AgentInput input) {
        Map<String, Object> target =
                object("message", message(input.message()), "variables", input.variables());
        return new Projection(
                target,
                null,
                replacement -> {
                    Map<String, Object> value = shape(replacement, target);
                    return input.toBuilder()
                            .message(restoreMessage(input.message(), value.get("message")))
                            .variables(map(value.get("variables")))
                            .build();
                });
    }

    private static Projection output(AgentOutput output) {
        // structured 仅接受 JSON 值，未知 POJO 不能仅扫描 text 后静默放行。
        Map<String, Object> target =
                object("text", output.text(), "structured", output.structured());
        return new Projection(
                target,
                null,
                replacement -> {
                    Map<String, Object> value = shape(replacement, target);
                    same(target.get("structured"), value.get("structured"));
                    return output.withText(string(value.get("text")));
                });
    }

    private static Projection modelInput(ModelRequest request) {
        List<Object> messages = new ArrayList<>();
        for (GuardMessage message : request.messages()) {
            messages.add(message(message));
        }
        List<Object> tools = new ArrayList<>();
        for (ToolDefinition tool : request.tools()) {
            tools.add(
                    object(
                            "name",
                            tool.name(),
                            "description",
                            tool.description(),
                            "parametersSchema",
                            tool.parametersSchema() == null
                                    ? null
                                    : JsonValues.parse(tool.parametersSchema())));
        }
        Map<String, Object> target =
                object(
                        "model",
                        request.model(),
                        "messages",
                        messages,
                        "tools",
                        tools,
                        "parameters",
                        request.parameters());
        return new Projection(
                target,
                null,
                replacement -> {
                    Map<String, Object> value = shape(replacement, target);
                    same(target.get("model"), value.get("model"));
                    same(target.get("tools"), value.get("tools"));
                    same(target.get("parameters"), value.get("parameters"));
                    List<?> changed = list(value.get("messages"));
                    sameSize(changed, request.messages());
                    List<GuardMessage> restored = new ArrayList<>();
                    for (int i = 0; i < changed.size(); i++) {
                        restored.add(restoreMessage(request.messages().get(i), changed.get(i)));
                    }
                    return request.withMessages(restored);
                });
    }

    private static Projection modelOutput(ModelResponse response) {
        Map<String, Object> target =
                object(
                        "model",
                        response.model(),
                        "message",
                        message(response.message()),
                        "finishReason",
                        response.finishReason());
        return new Projection(
                target,
                null,
                replacement -> {
                    Map<String, Object> value = shape(replacement, target);
                    same(target.get("model"), value.get("model"));
                    same(target.get("finishReason"), value.get("finishReason"));
                    return response.withMessage(
                            restoreMessage(response.message(), value.get("message")));
                });
    }

    private static Projection toolInput(ToolCall call) {
        return new Projection(
                JsonValues.parseObject(call.arguments()),
                call.toolName(),
                replacement -> call.withArguments(JsonValues.write(map(replacement))));
    }

    private static Projection toolOutput(ToolResult result) {
        Map<String, Object> target =
                object(
                        "callId",
                        result.callId(),
                        "toolName",
                        result.toolName(),
                        "content",
                        result.content(),
                        "error",
                        result.isError());
        return new Projection(
                target,
                result.toolName(),
                replacement -> {
                    Map<String, Object> value = shape(replacement, target);
                    same(target.get("callId"), value.get("callId"));
                    same(target.get("toolName"), value.get("toolName"));
                    same(target.get("error"), value.get("error"));
                    return result.withContent(string(value.get("content")));
                });
    }

    private static Map<String, Object> message(GuardMessage message) {
        List<String> contents = new ArrayList<>();
        for (ContentPart part : message.contents()) {
            if (!(part instanceof ContentPart.Text)) {
                throw unsupported();
            }
            ContentPart.Text text = (ContentPart.Text) part;
            contents.add(text.text());
        }
        List<Object> calls = new ArrayList<>();
        for (ToolCall call : message.toolCalls()) {
            calls.add(
                    object(
                            "callId",
                            call.callId(),
                            "toolName",
                            call.toolName(),
                            "arguments",
                            JsonValues.parseObject(call.arguments())));
        }
        return object(
                "role",
                message.role().name(),
                "contents",
                contents,
                "toolCalls",
                calls,
                "toolCallId",
                message.toolCallId(),
                "name",
                message.name());
    }

    private static GuardMessage restoreMessage(GuardMessage original, Object replacement) {
        Map<String, Object> prior = message(original);
        Map<String, Object> value = shape(replacement, prior);
        for (String key : Java8.list("role", "toolCallId", "name")) {
            same(prior.get(key), value.get(key));
        }
        List<?> texts = list(value.get("contents"));
        sameSize(texts, original.contents());
        List<ContentPart> contents = new ArrayList<>();
        for (Object text : texts) {
            contents.add(ContentPart.text(string(text)));
        }
        List<?> calls = list(value.get("toolCalls"));
        sameSize(calls, original.toolCalls());
        List<ToolCall> restoredCalls = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = original.toolCalls().get(i);
            Map<String, Object> priorCall =
                    object(
                            "callId",
                            call.callId(),
                            "toolName",
                            call.toolName(),
                            "arguments",
                            JsonValues.parseObject(call.arguments()));
            Map<String, Object> changed = shape(calls.get(i), priorCall);
            same(priorCall.get("callId"), changed.get("callId"));
            same(priorCall.get("toolName"), changed.get("toolName"));
            restoredCalls.add(call.withArguments(JsonValues.write(map(changed.get("arguments")))));
        }
        return original.toBuilder().contents(contents).toolCalls(restoredCalls).build();
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return map(JsonValues.freeze(result));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw unsupported();
        }
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> shape(Object value, Map<String, Object> original) {
        Map<String, Object> changed = map(value);
        same(original.keySet(), changed.keySet());
        return changed;
    }

    private static List<?> list(Object value) {
        if (!(value instanceof List<?>)) {
            throw unsupported();
        }
        List<?> values = (List<?>) value;
        return values;
    }

    private static String string(Object value) {
        if (!(value instanceof String)) {
            throw unsupported();
        }
        String text = (String) value;
        return text;
    }

    private static void sameSize(List<?> changed, List<?> original) {
        if (changed.size() != original.size()) {
            throw unsupported();
        }
    }

    private static void same(Object original, Object replacement) {
        if (!Objects.equals(original, replacement)) {
            throw unsupported();
        }
    }

    private static IllegalArgumentException unsupported() {
        return new IllegalArgumentException("Unsupported AGT payload or structural rewrite");
    }
}
