package io.agt.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.GovernanceException;
import io.agt.core.Identifiers;
import io.agt.core.Java8;
import io.agt.core.PolicyRequest;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 默认拒绝的数据授权规则。
 *
 * <p>资源类型、租户、所有者和各操作权限均显式校验。操作权限逐项检查；不存在从读权限推导写权限。
 */
public final class DataRule {
    private final DataResource.Kind kind;
    private final Scope scope;
    private final Map<DataOperation, Set<String>> permissions;

    @JsonCreator
    public DataRule(
            @JsonProperty("kind") DataResource.Kind kind,
            @JsonProperty("scope") Scope scope,
            @JsonProperty("permissions") Map<DataOperation, Set<String>> permissions) {

        Objects.requireNonNull(kind);
        Objects.requireNonNull(scope);
        Objects.requireNonNull(permissions);
        EnumMap<DataOperation, Set<String>> copy =
                new EnumMap<DataOperation, Set<String>>(DataOperation.class);
        permissions.forEach(
                (operation, required) -> {
                    if ((kind == DataResource.Kind.RETRIEVAL)
                                    != (operation == DataOperation.RETRIEVE)
                            || required == null
                            || required.isEmpty()) {
                        throw new IllegalArgumentException("Invalid data permission");
                    }
                    required.forEach(Identifiers::require);
                    copy.put(operation, Java8.copySet(required));
                });
        permissions = Collections.unmodifiableMap(copy);

        this.kind = kind;
        this.scope = scope;
        this.permissions = permissions;
    }

    @JsonProperty("kind")
    public DataResource.Kind kind() {
        return kind;
    }

    @JsonProperty("scope")
    public Scope scope() {
        return scope;
    }

    @JsonProperty("permissions")
    public Map<DataOperation, Set<String>> permissions() {
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
        DataRule other = (DataRule) value;
        return Objects.equals(kind, other.kind)
                && Objects.equals(scope, other.scope)
                && Objects.equals(permissions, other.permissions);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(kind);
        result = 31 * result + Objects.hashCode(scope);
        result = 31 * result + Objects.hashCode(permissions);
        return result;
    }

    @Override
    public String toString() {
        return "DataRule[kind=" + kind + ", scope=" + scope + ", permissions=" + permissions + "]";
    }

    /**
     * 声明资源按租户、用户或其他范围隔离的规则。
     */
    public enum Scope {
        TENANT,
        PRINCIPAL
    }

    public void validate(PolicyRequest request) {
        Map<String, Object> resource = DataResource.resource(request.target());
        Map<?, ?> target = (Map<?, ?>) request.target();
        if (!kind.name().equals(resource.get("kind"))) {
            throw new GovernanceException("data_kind_denied");
        }
        if (!request.context().tenantId().equals(resource.get("tenantId"))) {
            throw new GovernanceException("data_tenant_denied");
        }
        if (scope == Scope.PRINCIPAL
                && !request.context().principalId().equals(resource.get("principalId"))) {
            throw new GovernanceException("data_owner_denied");
        }
        DataOperation operation;
        try {
            operation = DataOperation.valueOf((String) target.get("operation"));
        } catch (RuntimeException e) {
            throw new GovernanceException("invalid_data_target");
        }
        Set<String> required = permissions.get(operation);
        if (required == null) {
            throw new GovernanceException("data_operation_denied");
        }
        if (!request.context().permissions().containsAll(required)) {
            throw new GovernanceException("data_permission_denied");
        }
    }
}
