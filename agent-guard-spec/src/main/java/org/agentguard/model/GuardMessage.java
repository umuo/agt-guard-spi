package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 框架中立的对话消息。
 *
 * <ul>
 *     <li>ASSISTANT 消息可能携带 {@link #toolCalls()}；</li>
 *     <li>TOOL 消息通过 {@link #toolCallId()} 关联到对应的工具调用。</li>
 * </ul>
 */
public final class GuardMessage {

    private final Role role;
    private final List<ContentPart> contents;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String name;
    private final Map<String, Object> metadata;

    private GuardMessage(Builder b) {
        this.role = Immutables.requireNonNull(b.role, "role");
        this.contents = Immutables.list(b.contents);
        this.toolCalls = Immutables.list(b.toolCalls);
        this.toolCallId = b.toolCallId;
        this.name = b.name;
        this.metadata = Immutables.map(b.metadata);
    }

    public static GuardMessage system(String text) {
        return builder().role(Role.SYSTEM).text(text).build();
    }

    public static GuardMessage user(String text) {
        return builder().role(Role.USER).text(text).build();
    }

    public static GuardMessage assistant(String text) {
        return builder().role(Role.ASSISTANT).text(text).build();
    }

    public static GuardMessage tool(String toolCallId, String toolName, String content) {
        return builder().role(Role.TOOL).toolCallId(toolCallId).name(toolName).text(content).build();
    }

    public Role role() {
        return role;
    }

    public List<ContentPart> contents() {
        return contents;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public String toolCallId() {
        return toolCallId;
    }

    /** 发送者名称；TOOL 消息为工具名。 */
    public String name() {
        return name;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    /** 拼接所有文本片段（以换行分隔），无文本时返回空串。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ContentPart part : contents) {
            if (part instanceof ContentPart.Text) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(((ContentPart.Text) part).text());
            }
        }
        return sb.toString();
    }

    /** 用一段新文本替换所有文本片段，媒体片段保持原顺序保留。常用于脱敏改写。 */
    public GuardMessage withText(String newText) {
        List<ContentPart> newContents = new ArrayList<ContentPart>();
        newContents.add(ContentPart.text(newText));
        for (ContentPart part : contents) {
            if (!(part instanceof ContentPart.Text)) {
                newContents.add(part);
            }
        }
        return toBuilder().contents(newContents).build();
    }

    public GuardMessage withToolCalls(List<ToolCall> newToolCalls) {
        return toBuilder().toolCalls(newToolCalls).build();
    }

    public Builder toBuilder() {
        return new Builder().role(role).contents(contents).toolCalls(toolCalls)
                .toolCallId(toolCallId).name(name).metadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "GuardMessage{role=" + role + ", contents=" + contents
                + ", toolCalls=" + toolCalls + ", toolCallId=" + toolCallId + '}';
    }

    public static final class Builder {
        private Role role;
        private List<ContentPart> contents = new ArrayList<ContentPart>();
        private List<ToolCall> toolCalls = Collections.emptyList();
        private String toolCallId;
        private String name;
        private Map<String, Object> metadata;

        private Builder() {
        }

        public Builder role(Role role) {
            this.role = role;
            return this;
        }

        /** 追加一个文本片段。 */
        public Builder text(String text) {
            if (text != null) {
                this.contents.add(ContentPart.text(text));
            }
            return this;
        }

        /** 追加一个内容片段。 */
        public Builder addContent(ContentPart part) {
            if (part != null) {
                this.contents.add(part);
            }
            return this;
        }

        /** 整体替换内容片段。 */
        public Builder contents(List<ContentPart> contents) {
            this.contents = contents == null ? new ArrayList<ContentPart>() : new ArrayList<ContentPart>(contents);
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public GuardMessage build() {
            return new GuardMessage(this);
        }
    }
}
