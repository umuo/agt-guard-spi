package io.agt.core.audit;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.EnforcementMode;
import io.agt.core.InterventionPoint;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 结构化治理审计记录。
 *
 * <p>不含上下文身份原文与策略目标，只保留宿主 agent ID 和内容摘要。
 */
public final class AuditRecord {
    private final int schemaVersion;
    private final Instant timestamp;
    private final String sessionId;
    private final String agentId;
    private final String actionId;
    private final InterventionPoint point;
    private final EnforcementMode mode;
    private final String policyVersion;
    private final String verdict;
    private final String reasonCode;
    private final boolean transformApplied;
    private final String approvalOutcome;
    private final String executionOutcome;
    private final String inputIdentity;
    private final String enforcedIdentity;
    private final long durationNanos;
    private final List<String> warnings;
    private final String dataOperation;
    private final String resourceIdentity;

    @JsonCreator
    public AuditRecord(
            @JsonProperty("schemaVersion") int schemaVersion,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("sessionId") String sessionId,
            @JsonProperty("agentId") String agentId,
            @JsonProperty("actionId") String actionId,
            @JsonProperty("point") InterventionPoint point,
            @JsonProperty("mode") EnforcementMode mode,
            @JsonProperty("policyVersion") String policyVersion,
            @JsonProperty("verdict") String verdict,
            @JsonProperty("reasonCode") String reasonCode,
            @JsonProperty("transformApplied") boolean transformApplied,
            @JsonProperty("approvalOutcome") String approvalOutcome,
            @JsonProperty("executionOutcome") String executionOutcome,
            @JsonProperty("inputIdentity") String inputIdentity,
            @JsonProperty("enforcedIdentity") String enforcedIdentity,
            @JsonProperty("durationNanos") long durationNanos,
            @JsonProperty("warnings") List<String> warnings,
            @JsonProperty("dataOperation") String dataOperation,
            @JsonProperty("resourceIdentity") String resourceIdentity) {

        this.schemaVersion = schemaVersion;
        this.timestamp = timestamp;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.actionId = actionId;
        this.point = point;
        this.mode = mode;
        this.policyVersion = policyVersion;
        this.verdict = verdict;
        this.reasonCode = reasonCode;
        this.transformApplied = transformApplied;
        this.approvalOutcome = approvalOutcome;
        this.executionOutcome = executionOutcome;
        this.inputIdentity = inputIdentity;
        this.enforcedIdentity = enforcedIdentity;
        this.durationNanos = durationNanos;
        this.warnings = warnings;
        this.dataOperation = dataOperation;
        this.resourceIdentity = resourceIdentity;
    }

    @JsonProperty("schemaVersion")
    public int schemaVersion() {
        return schemaVersion;
    }

    @JsonProperty("timestamp")
    public Instant timestamp() {
        return timestamp;
    }

    @JsonProperty("sessionId")
    public String sessionId() {
        return sessionId;
    }

    @JsonProperty("agentId")
    public String agentId() {
        return agentId;
    }

    @JsonProperty("actionId")
    public String actionId() {
        return actionId;
    }

    @JsonProperty("point")
    public InterventionPoint point() {
        return point;
    }

    @JsonProperty("mode")
    public EnforcementMode mode() {
        return mode;
    }

    @JsonProperty("policyVersion")
    public String policyVersion() {
        return policyVersion;
    }

    @JsonProperty("verdict")
    public String verdict() {
        return verdict;
    }

    @JsonProperty("reasonCode")
    public String reasonCode() {
        return reasonCode;
    }

    @JsonProperty("transformApplied")
    public boolean transformApplied() {
        return transformApplied;
    }

    @JsonProperty("approvalOutcome")
    public String approvalOutcome() {
        return approvalOutcome;
    }

    @JsonProperty("executionOutcome")
    public String executionOutcome() {
        return executionOutcome;
    }

    @JsonProperty("inputIdentity")
    public String inputIdentity() {
        return inputIdentity;
    }

    @JsonProperty("enforcedIdentity")
    public String enforcedIdentity() {
        return enforcedIdentity;
    }

    @JsonProperty("durationNanos")
    public long durationNanos() {
        return durationNanos;
    }

    @JsonProperty("warnings")
    public List<String> warnings() {
        return warnings;
    }

    @JsonProperty("dataOperation")
    public String dataOperation() {
        return dataOperation;
    }

    @JsonProperty("resourceIdentity")
    public String resourceIdentity() {
        return resourceIdentity;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        AuditRecord other = (AuditRecord) value;
        return Objects.equals(schemaVersion, other.schemaVersion)
                && Objects.equals(timestamp, other.timestamp)
                && Objects.equals(sessionId, other.sessionId)
                && Objects.equals(agentId, other.agentId)
                && Objects.equals(actionId, other.actionId)
                && Objects.equals(point, other.point)
                && Objects.equals(mode, other.mode)
                && Objects.equals(policyVersion, other.policyVersion)
                && Objects.equals(verdict, other.verdict)
                && Objects.equals(reasonCode, other.reasonCode)
                && Objects.equals(transformApplied, other.transformApplied)
                && Objects.equals(approvalOutcome, other.approvalOutcome)
                && Objects.equals(executionOutcome, other.executionOutcome)
                && Objects.equals(inputIdentity, other.inputIdentity)
                && Objects.equals(enforcedIdentity, other.enforcedIdentity)
                && Objects.equals(durationNanos, other.durationNanos)
                && Objects.equals(warnings, other.warnings)
                && Objects.equals(dataOperation, other.dataOperation)
                && Objects.equals(resourceIdentity, other.resourceIdentity);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(schemaVersion);
        result = 31 * result + Objects.hashCode(timestamp);
        result = 31 * result + Objects.hashCode(sessionId);
        result = 31 * result + Objects.hashCode(agentId);
        result = 31 * result + Objects.hashCode(actionId);
        result = 31 * result + Objects.hashCode(point);
        result = 31 * result + Objects.hashCode(mode);
        result = 31 * result + Objects.hashCode(policyVersion);
        result = 31 * result + Objects.hashCode(verdict);
        result = 31 * result + Objects.hashCode(reasonCode);
        result = 31 * result + Objects.hashCode(transformApplied);
        result = 31 * result + Objects.hashCode(approvalOutcome);
        result = 31 * result + Objects.hashCode(executionOutcome);
        result = 31 * result + Objects.hashCode(inputIdentity);
        result = 31 * result + Objects.hashCode(enforcedIdentity);
        result = 31 * result + Objects.hashCode(durationNanos);
        result = 31 * result + Objects.hashCode(warnings);
        result = 31 * result + Objects.hashCode(dataOperation);
        result = 31 * result + Objects.hashCode(resourceIdentity);
        return result;
    }

    @Override
    public String toString() {
        return "AuditRecord[schemaVersion="
                + schemaVersion
                + ", timestamp="
                + timestamp
                + ", sessionId="
                + sessionId
                + ", agentId="
                + agentId
                + ", actionId="
                + actionId
                + ", point="
                + point
                + ", mode="
                + mode
                + ", policyVersion="
                + policyVersion
                + ", verdict="
                + verdict
                + ", reasonCode="
                + reasonCode
                + ", transformApplied="
                + transformApplied
                + ", approvalOutcome="
                + approvalOutcome
                + ", executionOutcome="
                + executionOutcome
                + ", inputIdentity="
                + inputIdentity
                + ", enforcedIdentity="
                + enforcedIdentity
                + ", durationNanos="
                + durationNanos
                + ", warnings="
                + warnings
                + ", dataOperation="
                + dataOperation
                + ", resourceIdentity="
                + resourceIdentity
                + "]";
    }

    public AuditRecord(
            int schemaVersion,
            Instant timestamp,
            String sessionId,
            String agentId,
            String actionId,
            InterventionPoint point,
            EnforcementMode mode,
            String policyVersion,
            String verdict,
            String reasonCode,
            boolean transformApplied,
            String approvalOutcome,
            String executionOutcome,
            String inputIdentity,
            String enforcedIdentity,
            long durationNanos,
            List<String> warnings) {
        this(
                schemaVersion,
                timestamp,
                sessionId,
                agentId,
                actionId,
                point,
                mode,
                policyVersion,
                verdict,
                reasonCode,
                transformApplied,
                approvalOutcome,
                executionOutcome,
                inputIdentity,
                enforcedIdentity,
                durationNanos,
                warnings,
                null,
                null);
    }
}
