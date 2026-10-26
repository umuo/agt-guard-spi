package org.agentguard.context;

import org.agentguard.GuardStage;
import org.agentguard.util.Immutables;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 护栏上下文：贯穿一次 Agent 请求的身份与环境信息。
 *
 * <ul>
 *     <li><b>请求级字段</b>（traceId / tenantId / userId ...）在整个请求中保持不变；</li>
 *     <li><b>阶段级字段</b>（stage / iteration）通过 {@link #withStage} / {@link #withIteration} 派生新实例；</li>
 *     <li>{@link #attributes()} 为只读扩展信息（角色、渠道、IP 等，键见 {@link StandardAttributes}）；</li>
 *     <li>{@link #state()} 为<b>同一请求内所有派生实例共享</b>的可变状态，用于跨阶段传递信息，
 *         例如 AGENT_INPUT 阶段算出的风险分，在 TOOL_INPUT 阶段收紧权限。</li>
 * </ul>
 *
 * <p>本类不可变（state 除外）且线程安全。</p>
 */
public final class GuardContext {

    private final String traceId;
    private final String tenantId;
    private final String userId;
    private final String sessionId;
    private final String agentId;
    private final GuardStage stage;
    private final int iteration;
    private final Map<String, Object> attributes;
    private final ConcurrentMap<String, Object> state;

    private GuardContext(Builder b, ConcurrentMap<String, Object> state) {
        this.traceId = b.traceId;
        this.tenantId = b.tenantId;
        this.userId = b.userId;
        this.sessionId = b.sessionId;
        this.agentId = b.agentId;
        this.stage = b.stage;
        this.iteration = b.iteration;
        this.attributes = Immutables.map(b.attributes);
        this.state = state;
    }

    public String traceId() {
        return traceId;
    }

    public String tenantId() {
        return tenantId;
    }

    public String userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String agentId() {
        return agentId;
    }

    /** 当前阶段；由 Engine 在调用 Guard 前设置，Guard 内部永远非 null。 */
    public GuardStage stage() {
        return stage;
    }

    /** 当前 ReAct 轮次，从 0 开始；Agent 级阶段恒为 0。 */
    public int iteration() {
        return iteration;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }

    public <T> Optional<T> attribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.<T>empty();
    }

    /** 请求内共享的可变状态，所有通过 with* 派生的实例共享同一个 Map。 */
    public ConcurrentMap<String, Object> state() {
        return state;
    }

    public GuardContext withStage(GuardStage newStage) {
        if (newStage == this.stage) {
            return this;
        }
        return new GuardContext(toBuilder().stage(newStage), state);
    }

    public GuardContext withIteration(int newIteration) {
        if (newIteration == this.iteration) {
            return this;
        }
        return new GuardContext(toBuilder().iteration(newIteration), state);
    }

    /** 注意：toBuilder().build() 会创建<b>新的</b> state；派生同一请求的上下文请用 with* 方法。 */
    public Builder toBuilder() {
        return new Builder().traceId(traceId).tenantId(tenantId).userId(userId).sessionId(sessionId)
                .agentId(agentId).stage(stage).iteration(iteration).attributes(attributes);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "GuardContext{traceId=" + traceId + ", tenantId=" + tenantId + ", userId=" + userId
                + ", sessionId=" + sessionId + ", agentId=" + agentId + ", stage=" + stage
                + ", iteration=" + iteration + '}';
    }

    public static final class Builder {
        private String traceId;
        private String tenantId;
        private String userId;
        private String sessionId;
        private String agentId;
        private GuardStage stage;
        private int iteration;
        private Map<String, Object> attributes;

        private Builder() {
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder stage(GuardStage stage) {
            this.stage = stage;
            return this;
        }

        public Builder iteration(int iteration) {
            if (iteration < 0) {
                throw new IllegalArgumentException("iteration must be >= 0");
            }
            this.iteration = iteration;
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        public GuardContext build() {
            return new GuardContext(this, new ConcurrentHashMap<String, Object>());
        }
    }
}
