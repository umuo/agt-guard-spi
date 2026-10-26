package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.Map;

/**
 * Agent 入口输入（AGENT_INPUT 阶段的 payload）。
 */
public final class AgentInput {

    private final GuardMessage message;
    private final Map<String, Object> variables;
    private final Map<String, Object> metadata;

    private AgentInput(Builder b) {
        this.message = Immutables.requireNonNull(b.message, "message");
        this.variables = Immutables.map(b.variables);
        this.metadata = Immutables.map(b.metadata);
    }

    public static AgentInput of(String userText) {
        return builder().message(GuardMessage.user(userText)).build();
    }

    public static AgentInput of(GuardMessage message) {
        return builder().message(message).build();
    }

    /** 用户本次输入的消息。 */
    public GuardMessage message() {
        return message;
    }

    /** 便捷方法：用户输入的文本。 */
    public String text() {
        return message.text();
    }

    /** Prompt 模板变量等（如有）。 */
    public Map<String, Object> variables() {
        return variables;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public AgentInput withMessage(GuardMessage newMessage) {
        return toBuilder().message(newMessage).build();
    }

    public AgentInput withText(String newText) {
        return withMessage(message.withText(newText));
    }

    public Builder toBuilder() {
        return new Builder().message(message).variables(variables).metadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "AgentInput{message=" + message + '}';
    }

    public static final class Builder {
        private GuardMessage message;
        private Map<String, Object> variables;
        private Map<String, Object> metadata;

        private Builder() {
        }

        public Builder message(GuardMessage message) {
            this.message = message;
            return this;
        }

        public Builder variables(Map<String, Object> variables) {
            this.variables = variables;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public AgentInput build() {
            return new AgentInput(this);
        }
    }
}
