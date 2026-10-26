package io.agt.core.approval;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 外部审批决策响应。
 *
 * <p>包含关联的审批请求标识、动作摘要哈希以及是否批准的布尔结论。
 */
public final class ApprovalResponse {
    private final String requestId;
    private final String actionIdentity;
    private final boolean approved;

    @JsonCreator
    public ApprovalResponse(
            @JsonProperty("requestId") String requestId,
            @JsonProperty("actionIdentity") String actionIdentity,
            @JsonProperty("approved") boolean approved) {

        this.requestId = requestId;
        this.actionIdentity = actionIdentity;
        this.approved = approved;
    }

    @JsonProperty("requestId")
    public String requestId() {
        return requestId;
    }

    @JsonProperty("actionIdentity")
    public String actionIdentity() {
        return actionIdentity;
    }

    @JsonProperty("approved")
    public boolean approved() {
        return approved;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        ApprovalResponse other = (ApprovalResponse) value;
        return Objects.equals(requestId, other.requestId)
                && Objects.equals(actionIdentity, other.actionIdentity)
                && Objects.equals(approved, other.approved);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(requestId);
        result = 31 * result + Objects.hashCode(actionIdentity);
        result = 31 * result + Objects.hashCode(approved);
        return result;
    }

    @Override
    public String toString() {
        return "ApprovalResponse[requestId="
                + requestId
                + ", actionIdentity="
                + actionIdentity
                + ", approved="
                + approved
                + "]";
    }

    public static ApprovalResponse allow(ApprovalRequest r) {
        return new ApprovalResponse(r.requestId(), r.actionIdentity(), true);
    }

    public static ApprovalResponse deny(ApprovalRequest r) {
        return new ApprovalResponse(r.requestId(), r.actionIdentity(), false);
    }
}
