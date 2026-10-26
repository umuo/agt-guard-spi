package io.agt.core.approval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.PolicyRequest;
import java.util.Objects;

/**
 * 人工或外部系统审批请求。
 *
 * <p>包含请求标识、动作摘要、策略版本及超时过期绝对时间戳。
 */
public final class ApprovalRequest {
    private final String requestId;
    private final String actionIdentity;
    private final String policyVersion;
    private final PolicyRequest action;
    private final long expiresAtEpochMillis;

    @JsonCreator
    public ApprovalRequest(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("actionIdentity") String actionIdentity,
            @JsonProperty("policyVersion") String policyVersion,
            @JsonProperty("action") PolicyRequest action,
            @JsonProperty("expiresAtEpochMillis") long expiresAtEpochMillis) {

        this.requestId = requestId;
        this.actionIdentity = actionIdentity;
        this.policyVersion = policyVersion;
        this.action = action;
        this.expiresAtEpochMillis = expiresAtEpochMillis;
    }

    @JsonProperty("requestId")
    public String requestId() {
        return requestId;
    }

    @JsonProperty("actionIdentity")
    public String actionIdentity() {
        return actionIdentity;
    }

    @JsonProperty("policyVersion")
    public String policyVersion() {
        return policyVersion;
    }

    @JsonProperty("action")
    public PolicyRequest action() {
        return action;
    }

    @JsonProperty("expiresAtEpochMillis")
    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        ApprovalRequest other = (ApprovalRequest) value;
        return Objects.equals(requestId, other.requestId)
                && Objects.equals(actionIdentity, other.actionIdentity)
                && Objects.equals(policyVersion, other.policyVersion)
                && Objects.equals(action, other.action)
                && Objects.equals(expiresAtEpochMillis, other.expiresAtEpochMillis);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(requestId);
        result = 31 * result + Objects.hashCode(actionIdentity);
        result = 31 * result + Objects.hashCode(policyVersion);
        result = 31 * result + Objects.hashCode(action);
        result = 31 * result + Objects.hashCode(expiresAtEpochMillis);
        return result;
    }

    @Override
    public String toString() {
        return "ApprovalRequest[" + requestId + "]";
    }
}
