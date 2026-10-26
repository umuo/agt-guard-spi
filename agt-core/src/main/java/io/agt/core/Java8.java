package io.agt.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * 集中保留新版 JDK 便利 API 的边界语义，供 Java 8 运行时使用。
 * 集合工厂拒绝 null、重复键及重复元素，复制工厂执行防御性复制。
 */
public final class Java8 {
    private Java8() {}

    @SafeVarargs
    public static <T> List<T> list(T... values) {
        return copyList(Arrays.asList(values));
    }

    public static <T> List<T> copyList(Collection<? extends T> values) {
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(Objects.requireNonNull(value));
        }
        return Collections.unmodifiableList(result);
    }

    @SafeVarargs
    public static <T> Set<T> set(T... values) {
        Set<T> result = new LinkedHashSet<>();
        for (T value : values) {
            if (!result.add(Objects.requireNonNull(value))) {
                throw new IllegalArgumentException("Duplicate set element");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public static <T> Set<T> copySet(Collection<? extends T> values) {
        Set<T> result = new LinkedHashSet<>();
        for (T value : values) {
            result.add(Objects.requireNonNull(value));
        }
        return Collections.unmodifiableSet(result);
    }

    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> map(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Map needs key/value pairs");
        }
        Map<K, V> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            K key = (K) Objects.requireNonNull(pairs[i]);
            V value = (V) Objects.requireNonNull(pairs[i + 1]);
            if (result.containsKey(key)) {
                throw new IllegalArgumentException("Duplicate map key");
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    public static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> values) {
        Map<K, V> result = new LinkedHashMap<>();
        values.forEach(
                (key, value) ->
                        result.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(result);
    }

    public static <T> Collector<T, ?, List<T>> toList() {
        // Stream.toList 允许 null，不能直接复用拒绝 null 的 copyList。
        return Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList);
    }

    public static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(Objects.requireNonNull(error));
        return result;
    }

    public static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = alphabet[value >>> 4];
            result[i * 2 + 1] = alphabet[value & 15];
        }
        return new String(result);
    }

    public static String readString(Path path) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
                .toString();
    }

    public static Path writeString(Path path, CharSequence text, OpenOption... options)
            throws IOException {
        return Files.write(path, text.toString().getBytes(StandardCharsets.UTF_8), options);
    }

    public static boolean isBlank(String text) {
        return text.codePoints().allMatch(Character::isWhitespace);
    }

    public static String repeat(String text, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative repeat count");
        }
        StringBuilder result = new StringBuilder(Math.multiplyExact(text.length(), count));
        for (int i = 0; i < count; i++) {
            result.append(text);
        }
        return result.toString();
    }
}
