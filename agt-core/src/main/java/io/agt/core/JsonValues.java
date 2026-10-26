package io.agt.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 严格有界的 JSON 与数据摘要工具类。
 *
 * <p>有界严格 JSON 和仅在本项目内稳定的摘要；不声称 RFC 8785 / ACS 兼容。
 */
public final class JsonValues {
    public static final int MAX_CHARACTERS = 1_048_576;
    public static final int MAX_NODES = 8192;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_CONTAINER_SIZE = 4096;
    public static final int MAX_NUMBER_LENGTH = 128;
    private static final int MAX_NUMBER_BITS =
            (int) Math.ceil(MAX_NUMBER_LENGTH * (Math.log(10) / Math.log(2)));
    private static final JsonMapper MAPPER =
            JsonMapper.builder(
                            JsonFactory.builder()
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(MAX_DEPTH)
                                                    .maxStringLength(MAX_CHARACTERS)
                                                    .maxNumberLength(MAX_NUMBER_LENGTH)
                                                    .build())
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .build();

    private JsonValues() {}

    public static Object parse(String text) {
        if (text == null || text.length() > MAX_CHARACTERS) {
            throw new GovernanceException("invalid_json");
        }
        try {
            return freeze(MAPPER.readValue(text, Object.class));
        } catch (Exception e) {
            throw new GovernanceException("invalid_json");
        }
    }

    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map<?, ?>)) {
            throw new GovernanceException("invalid_tool_arguments");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) v;
        return map;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(freeze(value));
        } catch (Exception e) {
            throw new GovernanceException("unsupported_json_value");
        }
    }

    public static String digest(Object value) {
        try {
            return "sha256:"
                    + Java8.hex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(write(value).getBytes(StandardCharsets.UTF_8)));
        } catch (GovernanceException e) {
            throw e;
        } catch (Exception e) {
            throw new GovernanceException("digest_failed");
        }
    }

    public static Object freeze(Object value) {
        return freeze(value, 0, new SizeBudget());
    }

    /**
     * 在递归冻结过程中累计限制字符数及节点数。
     */
    private static final class SizeBudget {
        int chars = MAX_CHARACTERS;
        int nodes = MAX_NODES;

        void consume(int n) {
            chars -= n;
            if (chars < 0 || --nodes < 0) {
                throw new GovernanceException("payload_limit");
            }
        }
    }

    private static Object freeze(Object v, int depth, SizeBudget budget) {
        budget.consume(v instanceof String ? ((String) v).length() : 1);
        if (depth > MAX_DEPTH) {
            throw new GovernanceException("payload_limit");
        }
        if (v == null || v instanceof Boolean) {
            return v;
        }
        if (v instanceof String) {
            String s = (String) v;
            if (s.length() > MAX_CHARACTERS) {
                throw new GovernanceException("payload_limit");
            }
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i))) {
                        throw new GovernanceException("invalid_unicode");
                    }
                } else if (Character.isLowSurrogate(c)) {
                    throw new GovernanceException("invalid_unicode");
                }
            }
            return s;
        }
        if (v instanceof Number) {
            Number n = (Number) v;
            if ((n instanceof Double && !Double.isFinite(n.doubleValue()))
                    || (n instanceof Float && !Float.isFinite(n.floatValue()))) {
                throw new GovernanceException("invalid_number");
            }
            if (!(n instanceof Byte
                    || n instanceof Short
                    || n instanceof Integer
                    || n instanceof Long
                    || n instanceof Float
                    || n instanceof Double
                    || n instanceof BigDecimal
                    || n instanceof BigInteger)) {
                throw new GovernanceException("invalid_number");
            }
            // 拒绝超长数字时先检查位数，避免为超大 BigInteger/BigDecimal 分配完整十进制字符串。
            if ((n instanceof BigInteger && ((BigInteger) n).bitLength() > MAX_NUMBER_BITS)
                    || (n instanceof BigDecimal
                            && ((BigDecimal) n).unscaledValue().bitLength() > MAX_NUMBER_BITS)
                    || n.toString().length() > MAX_NUMBER_LENGTH) {
                throw new GovernanceException("payload_limit");
            }
            return n;
        }
        if (v instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) v;
            if (map.size() > MAX_CONTAINER_SIZE) {
                throw new GovernanceException("payload_limit");
            }
            Map<String, Object> copy = new TreeMap<>();
            map.forEach(
                    (k, item) -> {
                        if (!(k instanceof String)) {
                            throw new GovernanceException("unsupported_json_value");
                        }
                        String key = (String) k;
                        freeze(key, depth + 1, budget);
                        copy.put(key, freeze(item, depth + 1, budget));
                    });
            return Collections.unmodifiableMap(copy);
        }
        if (v instanceof List<?>) {
            List<?> list = (List<?>) v;
            if (list.size() > MAX_CONTAINER_SIZE) {
                throw new GovernanceException("payload_limit");
            }
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(freeze(item, depth + 1, budget)));
            return Collections.unmodifiableList(copy);
        }
        throw new GovernanceException("unsupported_json_value");
    }
}
