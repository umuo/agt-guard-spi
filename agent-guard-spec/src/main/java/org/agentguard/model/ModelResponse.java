package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.Map;

/**
 * 一次 LLM 调用的响应（MODEL_OUTPUT 阶段的 payload）。
 * 可能是最终文本，也可能是携带 tool_calls 的中间回复。
 */
public final class ModelResponse {

    private final String model;
    private final GuardMessage message;
    private final String finishReason;
    private final TokenUsage usage;
    private final Map<String, Object> metadata;

    private ModelResponse(Builder b) {
        this.model = b.model;
        this.message = Immutables.requireNonNull(b.message, "message");
        this.finishReason = b.finishReason;
        this.usage = b.usage;
        this.metadata = Immutables.map(b.metadata);
    }

    public static ModelResponse of(GuardMessage message) {
        return builder().message(message).build();
    }

    public String model() {
        return model;
    }

    /** 模型返回的 ASSISTANT 消息。 */
    public GuardMessage message() {
        return message;
    }

    public String text() {
        return message.text();
    }

    public boolean hasToolCalls() {
        return message.hasToolCalls();
    }

    public String finishReason() {
        return finishReason;
    }

    public TokenUsage usage() {
        return usage;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public ModelResponse withMessage(GuardMessage newMessage) {
        return toBuilder().message(newMessage).build();
    }

    public ModelResponse withText(String newText) {
        return withMessage(message.withText(newText));
    }

    public Builder toBuilder() {
        return new Builder().model(model).message(message).finishReason(finishReason)
                .usage(usage).metadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ModelResponse{model=" + model + ", message=" + message + ", finishReason=" + finishReason + '}';
    }

    public static final class Builder {
        private String model;
        private GuardMessage message;
        private String finishReason;
        private TokenUsage usage;
        private Map<String, Object> metadata;

        private Builder() {
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder message(GuardMessage message) {
            this.message = message;
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder usage(TokenUsage usage) {
            this.usage = usage;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ModelResponse build() {
            return new ModelResponse(this);
        }
    }
}
