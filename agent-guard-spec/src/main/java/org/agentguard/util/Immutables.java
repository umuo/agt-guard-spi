package org.agentguard.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变集合工具（JDK 8 没有 List.of / Map.copyOf）。
 * 仅供规范内部及实现方使用，null 一律视为空集合。
 */
public final class Immutables {

    private Immutables() {
    }

    public static <T> List<T> list(Collection<? extends T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public static <K, V> Map<K, V> map(Map<? extends K, ? extends V> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<K, V>(source));
    }

    public static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
