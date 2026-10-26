package io.agt.core.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.GovernanceException;
import io.agt.core.Java8;
import io.agt.core.JsonValues;
import io.agt.core.TrustedContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 参数校验规则定义。
 *
 * <p>闭合参数规则，不是完整 JSON Schema。所有 object 均拒绝未知字段。
 */
public final class ParameterRule {
    private final Type type;
    private final Map<String, ParameterRule> properties;
    private final Set<String> required;
    private final List<Object> enumValues;
    private final BigDecimal minimum;
    private final BigDecimal maximum;
    private final Integer minLength;
    private final Integer maxLength;
    private final String equalsContext;
    private final ParameterRule items;

    @JsonCreator
    public ParameterRule(
            @JsonProperty("type") Type type,
            @JsonProperty("properties") Map<String, ParameterRule> properties,
            @JsonProperty("required") Set<String> required,
            @JsonProperty("enumValues") List<Object> enumValues,
            @JsonProperty("minimum") BigDecimal minimum,
            @JsonProperty("maximum") BigDecimal maximum,
            @JsonProperty("minLength") Integer minLength,
            @JsonProperty("maxLength") Integer maxLength,
            @JsonProperty("equalsContext") String equalsContext,
            @JsonProperty("items") ParameterRule items) {

        Objects.requireNonNull(type);
        properties = properties == null ? Java8.map() : Java8.copyMap(properties);
        required = required == null ? Java8.set() : Java8.copySet(required);
        enumValues =
                enumValues == null
                        ? Java8.list()
                        : enumValues.stream().map(JsonValues::freeze).collect(Java8.toList());
        if (!properties.keySet().containsAll(required)) {
            throw new IllegalArgumentException("Unknown required property");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Invalid numeric range");
        }
        if ((minLength != null && minLength < 0)
                || (maxLength != null && maxLength < 0)
                || (minLength != null && maxLength != null && minLength > maxLength)) {
            throw new IllegalArgumentException("Invalid length range");
        }
        if (equalsContext != null
                && !Java8.set("tenantId", "principalId", "agentId").contains(equalsContext)) {
            throw new IllegalArgumentException("Unknown context field");
        }
        if (type != Type.OBJECT && (!properties.isEmpty() || !required.isEmpty())) {
            throw new IllegalArgumentException("Properties require object");
        }
        if (type != Type.ARRAY && items != null) {
            throw new IllegalArgumentException("Items require array");
        }
        if (type == Type.ARRAY && items == null) {
            throw new IllegalArgumentException("Array needs items");
        }
        if (type != Type.STRING && equalsContext != null) {
            throw new IllegalArgumentException("Context binding needs string");
        }
        if (type != Type.STRING && type != Type.ARRAY && (minLength != null || maxLength != null)) {
            throw new IllegalArgumentException("Invalid length constraint");
        }
        if (type != Type.INTEGER && type != Type.NUMBER && (minimum != null || maximum != null)) {
            throw new IllegalArgumentException("Invalid numeric constraint");
        }

        this.type = type;
        this.properties = properties;
        this.required = required;
        this.enumValues = enumValues;
        this.minimum = minimum;
        this.maximum = maximum;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.equalsContext = equalsContext;
        this.items = items;
    }

    @JsonProperty("type")
    public Type type() {
        return type;
    }

    @JsonProperty("properties")
    public Map<String, ParameterRule> properties() {
        return properties;
    }

    @JsonProperty("required")
    public Set<String> required() {
        return required;
    }

    @JsonProperty("enumValues")
    public List<Object> enumValues() {
        return enumValues;
    }

    @JsonProperty("minimum")
    public BigDecimal minimum() {
        return minimum;
    }

    @JsonProperty("maximum")
    public BigDecimal maximum() {
        return maximum;
    }

    @JsonProperty("minLength")
    public Integer minLength() {
        return minLength;
    }

    @JsonProperty("maxLength")
    public Integer maxLength() {
        return maxLength;
    }

    @JsonProperty("equalsContext")
    public String equalsContext() {
        return equalsContext;
    }

    @JsonProperty("items")
    public ParameterRule items() {
        return items;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        ParameterRule other = (ParameterRule) value;
        return Objects.equals(type, other.type)
                && Objects.equals(properties, other.properties)
                && Objects.equals(required, other.required)
                && Objects.equals(enumValues, other.enumValues)
                && Objects.equals(minimum, other.minimum)
                && Objects.equals(maximum, other.maximum)
                && Objects.equals(minLength, other.minLength)
                && Objects.equals(maxLength, other.maxLength)
                && Objects.equals(equalsContext, other.equalsContext)
                && Objects.equals(items, other.items);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(type);
        result = 31 * result + Objects.hashCode(properties);
        result = 31 * result + Objects.hashCode(required);
        result = 31 * result + Objects.hashCode(enumValues);
        result = 31 * result + Objects.hashCode(minimum);
        result = 31 * result + Objects.hashCode(maximum);
        result = 31 * result + Objects.hashCode(minLength);
        result = 31 * result + Objects.hashCode(maxLength);
        result = 31 * result + Objects.hashCode(equalsContext);
        result = 31 * result + Objects.hashCode(items);
        return result;
    }

    @Override
    public String toString() {
        return "ParameterRule[type="
                + type
                + ", properties="
                + properties
                + ", required="
                + required
                + ", enumValues="
                + enumValues
                + ", minimum="
                + minimum
                + ", maximum="
                + maximum
                + ", minLength="
                + minLength
                + ", maxLength="
                + maxLength
                + ", equalsContext="
                + equalsContext
                + ", items="
                + items
                + "]";
    }

    /**
     * 参数规则允许的 JSON 值类型。
     */
    public enum Type {
        STRING,
        INTEGER,
        NUMBER,
        BOOLEAN,
        OBJECT,
        ARRAY
    }

    public static ParameterRule string(String contextField) {
        return new ParameterRule(
                Type.STRING, null, null, null, null, null, null, 4096, contextField, null);
    }

    public static ParameterRule object(Map<String, ParameterRule> properties, String... required) {
        return new ParameterRule(
                Type.OBJECT,
                properties,
                Java8.set(required),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public void validate(Object value, TrustedContext context) {
        boolean valid;
        switch (type) {
            case STRING:
                valid = value instanceof String;
                break;
            case INTEGER:
                valid =
                        value instanceof Number
                                && decimal((Number) value).stripTrailingZeros().scale() <= 0;
                break;
            case NUMBER:
                valid = value instanceof Number;
                break;
            case BOOLEAN:
                valid = value instanceof Boolean;
                break;
            case OBJECT:
                valid = value instanceof Map<?, ?>;
                break;
            case ARRAY:
                valid = value instanceof List<?>;
                break;
            default:
                throw new IllegalStateException("Unknown enum value");
        }

        if (!valid) {
            fail();
        }
        if (!enumValues.isEmpty() && enumValues.stream().noneMatch(v -> equivalent(v, value))) {
            fail();
        }
        if (value instanceof Number) {
            Number n = (Number) value;
            BigDecimal d = decimal(n);
            if ((minimum != null && d.compareTo(minimum) < 0)
                    || (maximum != null && d.compareTo(maximum) > 0)) {
                fail();
            }
        }
        if (value instanceof String) {
            String s = (String) value;
            length(s.codePointCount(0, s.length()));
            if (equalsContext != null) {
                String expected;
                switch (equalsContext) {
                    case "tenantId":
                        expected = context.tenantId();
                        break;
                    case "principalId":
                        expected = context.principalId();
                        break;
                    case "agentId":
                        expected = context.agentId();
                        break;
                    default:
                        throw new IllegalStateException();
                }

                if (!s.equals(expected)) {
                    throw new GovernanceException("context_binding_denied");
                }
            }
        }
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (!properties.keySet().containsAll(map.keySet())
                    || !map.keySet().containsAll(required)) {
                fail();
            }
            map.forEach((key, item) -> properties.get(key).validate(item, context));
        }
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            length(list.size());
            list.forEach(v -> items.validate(v, context));
        }
    }

    private void length(int n) {
        if ((minLength != null && n < minLength) || (maxLength != null && n > maxLength)) {
            fail();
        }
    }

    private static boolean equivalent(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            Number x = (Number) a;
            Number y = (Number) b;
            return decimal(x).compareTo(decimal(y)) == 0;
        }
        return Objects.equals(a, b);
    }

    private static BigDecimal decimal(Number n) {
        return new BigDecimal(n.toString());
    }

    private static void fail() {
        throw new GovernanceException("tool_arguments_denied");
    }
}
