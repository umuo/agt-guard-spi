package io.agt.core.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.GovernanceException;
import io.agt.core.Identifiers;
import io.agt.core.InterventionPoint;
import io.agt.core.Java8;
import io.agt.core.JsonValues;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 内容匹配与改写规则。
 *
 * <p>字面规则：先检查所有 deny，再按声明顺序替换字符串值，不替换 object 的键。
 */
public final class ContentRule {
    private final String id;
    private final Set<InterventionPoint> points;
    private final List<String> denyContains;
    private final Map<String, String> replacements;

    @JsonCreator
    public ContentRule(
            @JsonProperty("id") String id,
            @JsonProperty("points") Set<InterventionPoint> points,
            @JsonProperty("denyContains") List<String> denyContains,
            @JsonProperty("replacements") Map<String, String> replacements) {

        Identifiers.require(id);
        points = Java8.copySet(points);
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Content rule needs points");
        }
        denyContains = denyContains == null ? Java8.list() : Java8.copyList(denyContains);
        replacements =
                replacements == null
                        ? Java8.map()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(replacements));
        if (denyContains.stream().anyMatch(String::isEmpty)
                || replacements.keySet().stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Empty content pattern");
        }
        denyContains.forEach(JsonValues::freeze);
        replacements.forEach(
                (k, v) -> {
                    JsonValues.freeze(k);
                    Objects.requireNonNull(v);
                    JsonValues.freeze(v);
                });

        this.id = id;
        this.points = points;
        this.denyContains = denyContains;
        this.replacements = replacements;
    }

    @JsonProperty("id")
    public String id() {
        return id;
    }

    @JsonProperty("points")
    public Set<InterventionPoint> points() {
        return points;
    }

    @JsonProperty("denyContains")
    public List<String> denyContains() {
        return denyContains;
    }

    @JsonProperty("replacements")
    public Map<String, String> replacements() {
        return replacements;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        ContentRule other = (ContentRule) value;
        return Objects.equals(id, other.id)
                && Objects.equals(points, other.points)
                && Objects.equals(denyContains, other.denyContains)
                && Objects.equals(replacements, other.replacements);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(id);
        result = 31 * result + Objects.hashCode(points);
        result = 31 * result + Objects.hashCode(denyContains);
        result = 31 * result + Objects.hashCode(replacements);
        return result;
    }

    @Override
    public String toString() {
        return "ContentRule[id="
                + id
                + ", points="
                + points
                + ", denyContains="
                + denyContains
                + ", replacements="
                + replacements
                + "]";
    }

    public boolean denies(Object target) {
        if (target instanceof String) {
            String s = (String) target;
            return denyContains.stream().anyMatch(s::contains);
        }
        if (target instanceof Map<?, ?>) {
            Map<?, ?> m = (Map<?, ?>) target;
            return m.entrySet().stream().anyMatch(e -> denies(e.getKey()) || denies(e.getValue()));
        }
        if (target instanceof List<?>) {
            List<?> l = (List<?>) target;
            return l.stream().anyMatch(this::denies);
        }
        return false;
    }

    public Object transform(Object target) {
        if (target instanceof String) {
            String s = (String) target;
            for (Map.Entry<String, String> e : replacements.entrySet()) {
                long projected = s.length();
                int offset = 0;
                int found;
                while ((found = s.indexOf(e.getKey(), offset)) >= 0) {
                    projected += (long) e.getValue().length() - e.getKey().length();
                    if (projected > 1_048_576) {
                        throw new GovernanceException("payload_limit");
                    }
                    offset = found + e.getKey().length();
                }
                s = s.replace(e.getKey(), e.getValue());
            }
            return s;
        }
        if (target instanceof Map<?, ?>) {
            Map<?, ?> m = (Map<?, ?>) target;
            Map<String, Object> result = new LinkedHashMap<>();
            m.forEach((k, v) -> result.put((String) k, transform(v)));
            return result;
        }
        if (target instanceof List<?>) {
            List<?> l = (List<?>) target;
            return l.stream().map(this::transform).collect(Java8.toList());
        }
        return target;
    }
}
