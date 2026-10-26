package io.agt.core.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.Identifiers;
import io.agt.core.Java8;
import java.util.Objects;
import java.util.Set;

/**
 * 单个工具的治理规则。
 *
 * <p>声明工具所需权限集合、参数校验规则以及是否需要人工审批确认。
 */
public final class ToolRule {
    private final Set<String> permissions;
    private final ParameterRule parameters;
    private final boolean approvalRequired;

    @JsonCreator
    public ToolRule(
            @JsonProperty("permissions") Set<String> permissions,
            @JsonProperty("parameters") ParameterRule parameters,
            @JsonProperty("approvalRequired") boolean approvalRequired) {

        permissions = permissions == null ? Java8.set() : Java8.copySet(permissions);
        permissions.forEach(Identifiers::require);
        if (parameters == null || parameters.type() != ParameterRule.Type.OBJECT) {
            throw new IllegalArgumentException("Tool arguments need object rule");
        }

        this.permissions = permissions;
        this.parameters = parameters;
        this.approvalRequired = approvalRequired;
    }

    @JsonProperty("permissions")
    public Set<String> permissions() {
        return permissions;
    }

    @JsonProperty("parameters")
    public ParameterRule parameters() {
        return parameters;
    }

    @JsonProperty("approvalRequired")
    public boolean approvalRequired() {
        return approvalRequired;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        ToolRule other = (ToolRule) value;
        return Objects.equals(permissions, other.permissions)
                && Objects.equals(parameters, other.parameters)
                && Objects.equals(approvalRequired, other.approvalRequired);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(permissions);
        result = 31 * result + Objects.hashCode(parameters);
        result = 31 * result + Objects.hashCode(approvalRequired);
        return result;
    }

    @Override
    public String toString() {
        return "ToolRule[permissions="
                + permissions
                + ", parameters="
                + parameters
                + ", approvalRequired="
                + approvalRequired
                + "]";
    }
}
