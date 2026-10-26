package io.agt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.agt.core.execution.ProcessCleanup;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 验证降级实现保留不可变性、值语义、反序列化校验及严格文件解码。
 */
class Java8CompatibilityTest {
    @Test
    void immutableFactoriesRejectNullsDuplicatesAndMutations() {
        assertThrows(NullPointerException.class, () -> Java8.list("value", null));
        assertThrows(NullPointerException.class, () -> Java8.map("key", null));
        assertThrows(IllegalArgumentException.class, () -> Java8.map("key", 1, "key", 2));
        assertThrows(IllegalArgumentException.class, () -> Java8.set("value", "value"));
        List<String> original = new ArrayList<>(Arrays.asList("one"));
        List<String> copied = Java8.copyList(original);
        original.add("two");
        assertEquals(Arrays.asList("one"), copied);
        assertThrows(UnsupportedOperationException.class, () -> copied.add("three"));
        Set<String> copiedSet = Java8.copySet(Arrays.asList("one", "one"));
        assertEquals(1, copiedSet.size());
        Map<String, String> map = Java8.map("one", "two");
        assertThrows(UnsupportedOperationException.class, () -> map.put("three", "four"));
    }

    @Test
    void streamCollectorRetainsNullAndRejectsMutation() {
        List<String> list = Stream.of("one", null).collect(Java8.toList());
        assertEquals(Arrays.asList("one", null), list);
        assertThrows(UnsupportedOperationException.class, () -> list.add("two"));
    }

    @Test
    void valueClassesKeepEqualityHashAndRedaction() {
        BudgetLimits first = new BudgetLimits(1, 2, 1000);
        BudgetLimits equal = new BudgetLimits(1, 2, 1000);
        assertEquals(first, equal);
        assertEquals(first.hashCode(), equal.hashCode());
        assertNotEquals(first, new BudgetLimits(2, 2, 1000));
        Set<String> permissions = new HashSet<>(Arrays.asList("read"));
        TrustedContext context =
                new TrustedContext("agent", "private-tenant", "private-user", permissions);
        permissions.add("write");
        assertFalse(context.permissions().contains("write"));
        assertFalse(context.toString().contains("private-tenant"));
        assertEquals(
                "PolicyRequest[INPUT, redacted]",
                new PolicyRequest(
                                InterventionPoint.INPUT,
                                context,
                                "session",
                                "action",
                                null,
                                "private-content")
                        .toString());
    }

    @Test
    void jsonCreatorsPreserveNamesAndConstructorValidation() throws Exception {
        JsonMapper mapper = new JsonMapper();
        BudgetLimits limits =
                mapper.readValue(
                        "{\"maxModelCalls\":1,\"maxToolCalls\":2,\"maxDurationMillis\":1000}",
                        BudgetLimits.class);
        assertEquals(new BudgetLimits(1, 2, 1000), limits);
        assertEquals(
                limits, mapper.readValue(mapper.writeValueAsString(limits), BudgetLimits.class));
        assertThrows(
                Exception.class,
                () ->
                        mapper.readValue(
                                "{\"maxModelCalls\":0,\"maxToolCalls\":2,\"maxDurationMillis\":1000}",
                                BudgetLimits.class));
    }

    @Test
    void utf8ReaderRemainsStrictAndFutureKeepsCause() throws Exception {
        Path file = Files.createTempFile("agt-java8-", ".txt");
        try {
            Files.write(file, new byte[] {(byte) 0xff});
            assertThrows(CharacterCodingException.class, () -> Java8.readString(file));
            Java8.writeString(file, "中文");
            assertEquals("中文", Java8.readString(file));
        } finally {
            Files.deleteIfExists(file);
        }
        IllegalStateException cause = new IllegalStateException("failure");
        assertEquals(
                cause,
                assertThrows(CompletionException.class, () -> Java8.failedFuture(cause).join())
                        .getCause());
        assertEquals("00ff10", Java8.hex(new byte[] {0, (byte) 0xff, 16}));
        assertTrue(Java8.isBlank(" \t\n\u2003"));
        assertFalse(Java8.isBlank("value"));
    }

    @Test
    void descendantCapabilityMatchesRuntime() throws Exception {
        boolean available;
        try {
            Process.class.getMethod("descendants");
            available = true;
        } catch (NoSuchMethodException unavailable) {
            available = false;
        }
        assertEquals(available, ProcessCleanup.supportsDescendants());
    }
}
