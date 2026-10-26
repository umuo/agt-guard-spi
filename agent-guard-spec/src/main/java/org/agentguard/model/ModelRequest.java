package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.List;
import java.util.Map;

/**
 * 一次 LLM 调用请求（MODEL_INPUT 阶段的 payload）。
 *
 * <p><b>增量检测</b>：ReAct 循环中消息会越积越多，适配层应通过 {@link #newMessagesFrom()}
 * 标出本轮新增消息的起始下标，Guard 优先只检测 {@link #newMessages()}，避免重复扫描历史。</p>
 */
public final class ModelRequest {

    private final String model;
    private final List<GuardMessage> messages;
    private final int newMessagesFrom;
    private final List<ToolDefinition> tools;
    private final Map<String, Object> parameters;
    private final Map<String, Object> metadata;

    private ModelRequest(Builder b) {
        this.model = b.model;
        this.messages = Immutables.list(b.messages);
        if (b.newMessagesFrom < 0 || b.newMessagesFrom > this.messages.size()) {
            throw new IllegalArgumentException("newMessagesFrom out of range: " + b.newMessagesFrom
                    + ", messages size: " + this.messages.size());
        }
        this.newMessagesFrom = b.newMessagesFrom;
        this.tools = Immutables.list(b.tools);
        this.parameters = Immutables.map(b.parameters);
        this.metadata = Immutables.map(b.metadata);
    }

    /** 模型名称，可能为 null。 */
    public String model() {
        return model;
    }

    /** 完整消息列表（含 system / 历史 / 本轮新增）。 */
    public List<GuardMessage> messages() {
        return messages;
    }

    /** 本轮新增消息的起始下标；默认 0 表示全部视为新增。 */
    public int newMessagesFrom() {
        return newMessagesFrom;
    }

    /** 本轮新增消息。 */
    public List<GuardMessage> newMessages() {
        return messages.subList(newMessagesFrom, messages.size());
    }

    /** 本次暴露给 LLM 的工具。 */
    public List<ToolDefinition> tools() {
        return tools;
    }

    /** 温度、maxTokens 等模型参数。 */
    public Map<String, Object> parameters() {
        return parameters;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public ModelRequest withMessages(List<GuardMessage> newMessages) {
        int from = Math.min(newMessagesFrom, newMessages == null ? 0 : newMessages.size());
        return toBuilder().messages(newMessages).newMessagesFrom(from).build();
    }

    public ModelRequest withTools(List<ToolDefinition> newTools) {
        return toBuilder().tools(newTools).build();
    }

    public Builder toBuilder() {
        return new Builder().model(model).messages(messages).newMessagesFrom(newMessagesFrom)
                .tools(tools).parameters(parameters).metadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "ModelRequest{model=" + model + ", messages=" + messages.size()
                + ", newMessagesFrom=" + newMessagesFrom + ", tools=" + tools.size() + '}';
    }

    public static final class Builder {
        private String model;
        private List<GuardMessage> messages;
        private int newMessagesFrom;
        private List<ToolDefinition> tools;
        private Map<String, Object> parameters;
        private Map<String, Object> metadata;

        private Builder() {
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<GuardMessage> messages) {
            this.messages = messages;
            return this;
        }

        public Builder newMessagesFrom(int newMessagesFrom) {
            this.newMessagesFrom = newMessagesFrom;
            return this;
        }

        public Builder tools(List<ToolDefinition> tools) {
            this.tools = tools;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public ModelRequest build() {
            return new ModelRequest(this);
        }
    }
}
