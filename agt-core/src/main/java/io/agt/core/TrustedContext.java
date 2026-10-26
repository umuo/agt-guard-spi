package io.agt.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import java.util.Set;

/**
 * 宿主注入的可信上下文。
 *
 * <p>仅由宿主认证入口构造；此类型本身不验证 token。
 */
public final class TrustedContext {
    private final String agentId;
    private final String tenantId;
    private final String principalId;
    private final Set<String> permissions;

    @JsonCreator
    public TrustedContext(
            @JsonProperty("agentId") String agentId,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("principalId") String principalId,
            @JsonProperty("permissions") Set<String> permissions) {

        Identifiers.require(agentId);
        Identifiers.require(tenantId);
        Identifiers.require(principalId);
        permissions = Java8.copySet(Objects.requireNonNull(permissions));
        if (permissions.size() > 128) {
            throw new IllegalArgumentException("Too many permissions");
        }
        permissions.forEach(Identifiers::require);

        this.agentId = agentId;
        this.tenantId = tenantId;
        this.principalId = principalId;
        this.permissions = permissions;
    }

    @JsonProperty("agentId")
    public String agentId() {
        return agentId;
    }

    @JsonProperty("tenantId")
    public String tenantId() {
        return tenantId;
    }

    @JsonProperty("principalId")
    public String principalId() {
        return principalId;
    }

    @JsonProperty("permissions")
    public Set<String> permissions() {
        return permissions;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        TrustedContext other = (TrustedContext) value;
        return Objects.equals(agentId, other.agentId)
                && Objects.equals(tenantId, other.tenantId)
                && Objects.equals(principalId, other.principalId)
                && Objects.equals(permissions, other.permissions);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(agentId);
        result = 31 * result + Objects.hashCode(tenantId);
        result = 31 * result + Objects.hashCode(principalId);
        result = 31 * result + Objects.hashCode(permissions);
        return result;
    }

    @Override
    public String toString() {
        return "TrustedContext[redacted]";
    }
}
