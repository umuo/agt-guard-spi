package io.agt.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 策略评估请求对象。
 *
 * <p>封装干预点、可信上下文、会话标识、动作标识、可选工具名以及待受检目标数据。
 */
public final class PolicyRequest {
    private final InterventionPoint point;
    private final TrustedContext context;
    private final String sessionId;
    private final String actionId;
    private final String toolName;
    private final Object target;

    @JsonCreator
    public PolicyRequest(
            @JsonProperty("point") InterventionPoint point,
            @JsonProperty("context") TrustedContext context,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("actionId") String actionId,
            @JsonProperty("toolName") String toolName,
            @JsonProperty("target") Object target) {

        Objects.requireNonNull(point);
        Objects.requireNonNull(context);
        Identifiers.require(sessionId);
        Identifiers.require(actionId);
        if (toolName != null) {
            Identifiers.require(toolName);
        }
        target = JsonValues.freeze(target);

        this.point = point;
        this.context = context;
        this.sessionId = sessionId;
        this.actionId = actionId;
        this.toolName = toolName;
        this.target = target;
    }

    @JsonProperty("point")
    public InterventionPoint point() {
        return point;
    }

    @JsonProperty("context")
    public TrustedContext context() {
        return context;
    }

    @JsonProperty("sessionId")
    public String sessionId() {
        return sessionId;
    }

    @JsonProperty("actionId")
    public String actionId() {
        return actionId;
    }

    @JsonProperty("toolName")
    public String toolName() {
        return toolName;
    }

    @JsonProperty("target")
    public Object target() {
        return target;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        PolicyRequest other = (PolicyRequest) value;
        return Objects.equals(point, other.point)
                && Objects.equals(context, other.context)
                && Objects.equals(sessionId, other.sessionId)
                && Objects.equals(actionId, other.actionId)
                && Objects.equals(toolName, other.toolName)
                && Objects.equals(target, other.target);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(point);
        result = 31 * result + Objects.hashCode(context);
        result = 31 * result + Objects.hashCode(sessionId);
        result = 31 * result + Objects.hashCode(actionId);
        result = 31 * result + Objects.hashCode(toolName);
        result = 31 * result + Objects.hashCode(target);
        return result;
    }

    public PolicyRequest withTarget(Object replacement) {
        return new PolicyRequest(point, context, sessionId, actionId, toolName, replacement);
    }

    @Override
    public String toString() {
        return "PolicyRequest[" + point + ", redacted]";
    }
}
