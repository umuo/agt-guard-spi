package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.Map;

/**
 * 工具执行结果（TOOL_OUTPUT 阶段的 payload）。
 */
public final class ToolResult {

    private final String callId;
    private final String toolName;
    private final String content;
    private final boolean error;
    private final Map<String, Object> metadata;

    private ToolResult(Builder b) {
        this.callId = b.callId;
        this.toolName = Immutables.requireNonNull(b.toolName, "toolName");
        this.content = b.content == null ? "" : b.content;
        this.error = b.error;
        this.metadata = Immutables.map(b.metadata);
    }

    public static ToolResult of(String callId, String toolName, String content) {
        return builder().callId(callId).toolName(toolName).content(content).build();
    }

    public static ToolResult error(String callId, String toolName, String errorMessage) {
        return builder().callId(callId).toolName(toolName).content(errorMessage).error(true).build();
    }

    public String callId() {
        return callId;
    }

    public String toolName() {
        return toolName;
    }

    /** 回传给 LLM 的结果文本。 */
    public String content() {
        return content;
    }

    public boolean isError() {
        return error;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public ToolResult withContent(String newContent) {
        return toBuilder().content(newContent).build();
    }

    public Builder toBuilder() {
        return new Builder().callId(callId).toolName(toolName).content(content).error(error).metadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ToolResult{callId=" + callId + ", toolName=" + toolName
                + ", error=" + error + ", length=" + content.length() + '}';
    }

    public static final class Builder {
        private String callId;
        private String toolName;
        private String content;
        private boolean error;
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

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder error(boolean error) {
            this.error = error;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ToolResult build() {
            return new ToolResult(this);
        }
    }
}
