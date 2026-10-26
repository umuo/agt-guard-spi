package io.agt.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.GovernanceException;
import io.agt.core.Identifiers;
import io.agt.core.Java8;
import io.agt.core.JsonValues;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 宿主绑定实际数据源的描述符。
 *
 * <p>不能从提示词或文档元数据创建可信所有权。instanceId 必须指向实际分区或记忆实例，选择器及来源 metadata 不证明所有权。
 */
public final class DataResource {
    private final String name;
    private final Kind kind;
    private final String tenantId;
    private final String principalId;
    private final String instanceId;

    @JsonCreator
    public DataResource(
            @JsonProperty("name") String name,
            @JsonProperty("kind") Kind kind,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("principalId") String principalId,
            @JsonProperty("instanceId") String instanceId) {

        Identifiers.require(name);
        Objects.requireNonNull(kind);
        Identifiers.require(tenantId);
        if (principalId != null) {
            Identifiers.require(principalId);
        }
        Identifiers.require(instanceId);

        this.name = name;
        this.kind = kind;
        this.tenantId = tenantId;
        this.principalId = principalId;
        this.instanceId = instanceId;
    }

    @JsonProperty("name")
    public String name() {
        return name;
    }

    @JsonProperty("kind")
    public Kind kind() {
        return kind;
    }

    @JsonProperty("tenantId")
    public String tenantId() {
        return tenantId;
    }

    @JsonProperty("principalId")
    public String principalId() {
        return principalId;
    }

    @JsonProperty("instanceId")
    public String instanceId() {
        return instanceId;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        DataResource other = (DataResource) value;
        return Objects.equals(name, other.name)
                && Objects.equals(kind, other.kind)
                && Objects.equals(tenantId, other.tenantId)
                && Objects.equals(principalId, other.principalId)
                && Objects.equals(instanceId, other.instanceId);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(name);
        result = 31 * result + Objects.hashCode(kind);
        result = 31 * result + Objects.hashCode(tenantId);
        result = 31 * result + Objects.hashCode(principalId);
        result = 31 * result + Objects.hashCode(instanceId);
        return result;
    }

    @Override
    public String toString() {
        return "DataResource[name="
                + name
                + ", kind="
                + kind
                + ", tenantId="
                + tenantId
                + ", principalId="
                + principalId
                + ", instanceId="
                + instanceId
                + "]";
    }

    /**
     * 区分检索与记忆等受治理的数据资源。
     */
    public enum Kind {
        RETRIEVAL,
        MEMORY
    }

    public Map<String, Object> descriptor() {
        LinkedHashMap<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("kind", kind.name());
        result.put("tenantId", tenantId);
        result.put("principalId", principalId);
        result.put("instanceId", instanceId);
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Object> target(DataOperation operation, Object value) {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Java8.map(
                                "resource",
                                descriptor(),
                                "operation",
                                operation.name(),
                                "value",
                                Collections.singletonList(value))));
    }

    public Object value(Object effective, DataOperation operation) {
        if (!(effective instanceof Map<?, ?>)) {
            throw new GovernanceException("data_identity_transform_unsupported");
        }
        Map<?, ?> target = (Map<?, ?>) effective;
        if (!target.keySet().equals(Java8.set("resource", "operation", "value"))
                || !descriptor().equals(target.get("resource"))
                || !operation.name().equals(target.get("operation"))
                || !(target.get("value") instanceof List<?>)
                || ((List<?>) target.get("value")).size() != 1) {
            throw new GovernanceException("data_identity_transform_unsupported");
        }
        return ((List<?>) target.get("value")).get(0);
    }

    public static Map<String, Object> resource(Object target) {
        if (!(target instanceof Map<?, ?>)
                || !(((Map<?, ?>) target).get("resource") instanceof Map<?, ?>)) {
            throw new GovernanceException("invalid_data_target");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                (Map<String, Object>) JsonValues.freeze(((Map<?, ?>) target).get("resource"));
        return result;
    }
}
