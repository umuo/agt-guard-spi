package io.agt.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agt.core.AgentControl;
import io.agt.core.BudgetLimits;
import io.agt.core.GovernanceException;
import io.agt.core.HostSession;
import io.agt.core.Java8;
import io.agt.core.TrustedContext;
import io.agt.core.policy.StructuredPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 固定程序边界测试。
 *
 * <p>实际启动 Java 子进程验证环境、输出、期限和会话终止；不执行模型提供的脚本。
 */
class FixedCommandTest {
    @TempDir Path directory;

    FixedCommand command(CommandLimits limits) throws Exception {
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java").toRealPath();
        Path classes =
                Paths.get(
                                CommandFixture.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .toRealPath();
        return new FixedCommand(
                "fixed",
                java,
                digest(java),
                directory,
                Java8.list(
                        CommandArgument.literal("-cp"),
                        CommandArgument.literal(classes.toString()),
                        CommandArgument.literal(CommandFixture.class.getName()),
                        CommandArgument.choice(
                                "mode",
                                "echo",
                                "environment",
                                "timeout",
                                "overflow",
                                "exit",
                                "invalid",
                                "literal;$(echo unsafe)"),
                        CommandArgument.literal(directory.resolve("effect").toString())),
                limits);
    }

    static String digest(Path file) throws Exception {
        return Java8.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    AgentControl control() {
        return AgentControl.builder(StructuredPolicy.builder("command-test").build())
                .audit(record -> {})
                .build();
    }

    static <T> T withSession(
            AgentControl control, TrustedContext context, Function<HostSession, T> action) {
        try (HostSession session = control.openSession(context)) {
            return action.apply(session);
        }
    }

    static TrustedContext context() {
        return new TrustedContext("agent", "tenant", "user", Java8.set());
    }

    @Test
    void fixedProcessHasNoInheritedEnvironment() throws Exception {
        FixedCommand command = command(CommandLimits.DEFAULT);
        try (AgentControl control = control()) {
            withSession(
                    control,
                    context(),
                    session -> {
                        assertEquals(
                                "empty",
                                command.execute(Java8.map("mode", "environment"), session));
                        return null;
                    });
        }
        assertTrue(Files.exists(directory.resolve("effect")));
    }

    @Test
    void allowedMetacharactersRemainOneLiteralArgument() throws Exception {
        FixedCommand command = command(CommandLimits.DEFAULT);
        try (AgentControl control = control();
                HostSession session = control.openSession(context())) {
            assertEquals(
                    "literal;$(echo unsafe)",
                    command.execute(Java8.map("mode", "literal;$(echo unsafe)"), session));
        }
    }

    @Test
    void sessionDeadlineRefusesProcessOutput() throws Exception {
        FixedCommand command = command(new CommandLimits(10000, 1000, 1));
        try (AgentControl control =
                        AgentControl.builder(
                                        StructuredPolicy.builder("deadline-test")
                                                .budget(new BudgetLimits(10, 10, 500))
                                                .build())
                                .audit(record -> {})
                                .build();
                HostSession session = control.openSession(context())) {
            assertEquals(
                    "session_expired",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> command.execute(Java8.map("mode", "timeout"), session))
                            .code());
            assertTrue(session.budgetSnapshot().failed());
        }
    }

    @Test
    void unknownMissingAndInjectedArgumentsNeverStartProcess() throws Exception {
        FixedCommand command = command(CommandLimits.DEFAULT);
        for (Map<String, Object> values :
                Java8.list(
                        Java8.<String, Object>map(),
                        Java8.<String, Object>map("mode", "echo", "command", "sh"),
                        Java8.<String, Object>map("mode", "; touch injected"),
                        Java8.<String, Object>map("mode", "--help"),
                        Java8.<String, Object>map("mode", "../elsewhere"),
                        Java8.<String, Object>map("mode", 1))) {
            assertEquals(
                    "command_arguments_denied",
                    assertThrows(GovernanceException.class, () -> command.prepare(values)).code());
        }
        assertFalse(Files.exists(directory.resolve("effect")));
    }

    @ParameterizedTest
    @CsvSource({
        "timeout,command_timeout",
        "overflow,command_output_limit",
        "exit,command_exit_denied",
        "invalid,command_output_failed"
    })
    void boundedProcessFailuresUseSanitizedCodes(String mode, String expected) throws Exception {
        FixedCommand command =
                command(new CommandLimits(mode.equals("timeout") ? 800 : 5000, 1000, 1));
        try (AgentControl control = control()) {
            withSession(
                    control,
                    context(),
                    session -> {
                        assertEquals(
                                expected,
                                assertThrows(
                                                GovernanceException.class,
                                                () ->
                                                        command.execute(
                                                                Java8.map("mode", mode), session))
                                        .code());
                        return null;
                    });
        }
        assertTrue(Files.exists(directory.resolve("effect")));
    }

    @Test
    void changedExecutableIsRefusedBeforeStarting() throws Exception {
        Path executable = directory.resolve("program");
        Java8.writeString(executable, "trusted");
        assertTrue(executable.toFile().setExecutable(true));
        FixedCommand command =
                new FixedCommand(
                        "fixed",
                        executable,
                        digest(executable),
                        directory,
                        Java8.list(),
                        CommandLimits.DEFAULT);
        Java8.writeString(executable, "modified");
        assertEquals(
                "command_integrity_denied",
                assertThrows(GovernanceException.class, () -> command.prepare(Java8.map())).code());
    }

    @Test
    void invalidProfilesAndWrongDigestFailAtRegistration() throws Exception {
        Path java = Paths.get(System.getProperty("java.home"), "bin", "java").toRealPath();
        assertEquals(
                "command_backend_required",
                assertThrows(
                                GovernanceException.class,
                                () ->
                                        new FixedCommand(
                                                "fixed",
                                                java,
                                                digest(java),
                                                directory,
                                                Java8.list(),
                                                CommandLimits.DEFAULT,
                                                null))
                        .code());
        assertThrows(
                GovernanceException.class,
                () ->
                        new FixedCommand(
                                "fixed",
                                java,
                                Java8.repeat("0", 64),
                                directory,
                                Java8.list(),
                                CommandLimits.DEFAULT));
        assertThrows(
                GovernanceException.class,
                () ->
                        new FixedCommand(
                                "fixed",
                                Paths.get("java"),
                                digest(java),
                                directory,
                                Java8.list(),
                                CommandLimits.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new CommandLimits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CommandArgument.choice("mode"));
        assertThrows(IllegalArgumentException.class, () -> CommandArgument.literal("bad\0arg"));
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, java);
        assertThrows(
                GovernanceException.class,
                () ->
                        new FixedCommand(
                                "fixed",
                                link,
                                digest(java),
                                directory,
                                Java8.list(),
                                CommandLimits.DEFAULT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoke", "close", "interrupt"})
    void terminationStopsWaitAndCapacityIsReleased(String stop) throws Exception {
        FixedCommand command = command(new CommandLimits(10000, 1000, 1));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (AgentControl control = control();
                HostSession session = control.openSession(context())) {
            Future<String> result =
                    worker.submit(() -> command.execute(Java8.map("mode", "timeout"), session));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(directory.resolve("effect")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(directory.resolve("effect")));
            assertEquals(
                    "command_capacity_exceeded",
                    assertThrows(
                                    GovernanceException.class,
                                    () -> command.execute(Java8.map("mode", "echo"), session))
                            .code());
            long pid = Long.parseLong(Java8.readString(directory.resolve("effect")));
            if (stop.equals("revoke")) {
                session.revoke();
            } else if (stop.equals("close")) {
                session.close();
            } else {
                result.cancel(true);
            }
            if (!stop.equals("interrupt")) {
                assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            }
            long cleanup = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (isAlive(pid) && System.nanoTime() < cleanup) {
                Thread.sleep(10);
            }
            assertFalse(isAlive(pid));
            withSession(
                    control,
                    context(),
                    next -> {
                        assertEquals(
                                "secret-result", command.execute(Java8.map("mode", "echo"), next));
                        return null;
                    });
        } finally {
            worker.shutdownNow();
        }
    }

    private static boolean isAlive(long pid) throws Exception {
        // 测试在 POSIX 宿主上使用系统查询，不让生产模块依赖平台命令。
        Process query = new ProcessBuilder("/bin/kill", "-0", Long.toString(pid)).start();
        return query.waitFor() == 0;
    }
}
