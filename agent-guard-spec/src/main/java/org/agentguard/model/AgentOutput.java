package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.Map;

/**
 * Agent 最终输出（AGENT_OUTPUT 阶段的 payload）。
 */
public final class AgentOutput {

    private final String text;
    private final Object structured;
    private final Map<String, Object> metadata;

    private AgentOutput(Builder b) {
        this.text = b.text == null ? "" : b.text;
        this.structured = b.structured;
        this.metadata = Immutables.map(b.metadata);
    }

    public static AgentOutput of(String text) {
        return builder().text(text).build();
    }

    /** 最终答复文本。 */
    public String text() {
        return text;
    }

    /** 结构化输出（如 AiServices 返回的 POJO），可能为 null。Guard 一般只检测 text。 */
    public Object structured() {
        return structured;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public AgentOutput withText(String newText) {
        return toBuilder().text(newText).build();
    }

    public Builder toBuilder() {
        return new Builder().text(text).structured(structured).metadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "AgentOutput{length=" + text.length() + ", structured=" + (structured != null) + '}';
    }

    public static final class Builder {
        private String text;
        private Object structured;
        private Map<String, Object> metadata;

        private Builder() {
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder structured(Object structured) {
            this.structured = structured;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public AgentOutput build() {
            return new AgentOutput(this);
        }
    }
}
