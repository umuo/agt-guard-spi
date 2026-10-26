package io.agt.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
 * 策略评估判定结果。
 *
 * <p>包含判定类型（放行、拒绝、改写）、原因代码、替换载荷与审批标记。
 */
public final class PolicyDecision {
    private final Kind kind;
    private final String reasonCode;
    private final Object replacement;
    private final boolean approvalRequired;
    private final List<String> warnings;

    @JsonCreator
    public PolicyDecision(
            @JsonProperty("kind") Kind kind,
            @JsonProperty("reasonCode") String reasonCode,
            @JsonProperty("replacement") Object replacement,
            @JsonProperty("approvalRequired") boolean approvalRequired,
            @JsonProperty("warnings") List<String> warnings) {

        Objects.requireNonNull(kind);
        Identifiers.require(reasonCode);
        warnings = Java8.copyList(warnings);
        warnings.forEach(Identifiers::require);
        if (approvalRequired && kind != Kind.DENY) {
            throw new IllegalArgumentException("Approval needs deny");
        }
        if (kind != Kind.TRANSFORM && replacement != null) {
            throw new IllegalArgumentException("Invalid replacement");
        }
        replacement = JsonValues.freeze(replacement);

        this.kind = kind;
        this.reasonCode = reasonCode;
        this.replacement = replacement;
        this.approvalRequired = approvalRequired;
        this.warnings = warnings;
    }

    @JsonProperty("kind")
    public Kind kind() {
        return kind;
    }

    @JsonProperty("reasonCode")
    public String reasonCode() {
        return reasonCode;
    }

    @JsonProperty("replacement")
    public Object replacement() {
        return replacement;
    }

    @JsonProperty("approvalRequired")
    public boolean approvalRequired() {
        return approvalRequired;
    }

    @JsonProperty("warnings")
    public List<String> warnings() {
        return warnings;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        PolicyDecision other = (PolicyDecision) value;
        return Objects.equals(kind, other.kind)
                && Objects.equals(reasonCode, other.reasonCode)
                && Objects.equals(replacement, other.replacement)
                && Objects.equals(approvalRequired, other.approvalRequired)
                && Objects.equals(warnings, other.warnings);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(kind);
        result = 31 * result + Objects.hashCode(reasonCode);
        result = 31 * result + Objects.hashCode(replacement);
        result = 31 * result + Objects.hashCode(approvalRequired);
        result = 31 * result + Objects.hashCode(warnings);
        return result;
    }

    /**
     * 策略评估的放行、拒绝与改写类型。
     */
    public enum Kind {
        ALLOW,
        DENY,
        TRANSFORM
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(Kind.ALLOW, "allowed", null, false, Java8.list());
    }

    public static PolicyDecision deny(String code) {
        return new PolicyDecision(Kind.DENY, code, null, false, Java8.list());
    }

    public static PolicyDecision approval(String code) {
        return new PolicyDecision(Kind.DENY, code, null, true, Java8.list());
    }

    public static PolicyDecision transform(String code, Object value) {
        return new PolicyDecision(Kind.TRANSFORM, code, value, false, Java8.list());
    }

    @Override
    public String toString() {
        return "PolicyDecision[" + kind + ", " + reasonCode + "]";
    }
}
