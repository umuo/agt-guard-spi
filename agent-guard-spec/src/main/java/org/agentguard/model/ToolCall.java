package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.Map;

/**
 * 一次工具调用请求（TOOL_INPUT 阶段的 payload）。
 * 参数统一为 JSON 字符串，由 Guard 按需解析，规范层不绑定任何 JSON 库。
 */
public final class ToolCall {

    private final String callId;
    private final String toolName;
    private final String arguments;
    private final Map<String, Object> metadata;

    private ToolCall(Builder b) {
        this.callId = b.callId;
        this.toolName = Immutables.requireNonNull(b.toolName, "toolName");
        this.arguments = b.arguments == null ? "{}" : b.arguments;
        this.metadata = Immutables.map(b.metadata);
    }

    public static ToolCall of(String callId, String toolName, String argumentsJson) {
        return builder().callId(callId).toolName(toolName).arguments(argumentsJson).build();
    }

    public String callId() {
        return callId;
    }

    public String toolName() {
        return toolName;
    }

    /** 工具入参，JSON 字符串。 */
    public String arguments() {
        return arguments;
    }

    /** 扩展信息，例如工具来源（本地 / MCP server）、工具分组、风险等级标签等。 */
    public Map<String, Object> metadata() {
        return metadata;
    }

    public ToolCall withArguments(String newArgumentsJson) {
        return toBuilder().arguments(newArgumentsJson).build();
    }

    public Builder toBuilder() {
        return new Builder().callId(callId).toolName(toolName).arguments(arguments).metadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ToolCall{callId=" + callId + ", toolName=" + toolName + '}';
    }

    public static final class Builder {
        private String callId;
        private String toolName;
        private String arguments;
        private Map<String, Object> metadata;

        private Builder() {
        }

        public Builder callId(String callId) {
            this.callId = callId;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder arguments(String arguments) {
            this.arguments = arguments;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ToolCall build() {
            return new ToolCall(this);
        }
    }
}
