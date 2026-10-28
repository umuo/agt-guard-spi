package org.agentguard.agt;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.agentguard.diagnostics.GuardDiagnostics;
import org.agentguard.engine.GuardEngine;
import org.agentguard.spi.GuardEngineFactory;
import org.junit.Assert;
import org.junit.Test;

/**
 * 使用实际 SPI 与独立 JVM 验证策略来源，诊断不保存路径或策略正文。
 * 环境变量通过子进程注入，不修改测试 JVM 的全局环境映射。
 */
public class AgtDiagnosticsTest {
    @Test
    public void programmaticPolicyHasSixStagesAndSafeSummary() {
        GuardEngine engine = AgtGuards.builder().denyText("private-deny-word").build();
        GuardDiagnostics report = GuardDiagnostics.inspect(engine);
        Assert.assertTrue(report.detailsAvailable());
        Assert.assertEquals(1, report.providers().size());
        Assert.assertEquals(
                GuardDiagnostics.PolicySource.PROGRAMMATIC,
                report.providers().get(0).policySource());
        Assert.assertTrue(report.providers().get(0).streaming());
        Assert.assertFalse(report.toString().contains("private-deny-word"));
        report.rulesByStage().values().forEach(rules -> Assert.assertEquals(1, rules.size()));
    }

    @Test
    public void actualSpiReportsSystemPropertyWithoutPath() throws Exception {
        String previous = System.getProperty(AgtGuardProvider.POLICY_PATH_PROPERTY);
        String path = Paths.get(getClass().getResource("/policy.yaml").toURI()).toString();
        try {
            System.setProperty(AgtGuardProvider.POLICY_PATH_PROPERTY, path);
            GuardEngineFactory factory =
                    java.util.ServiceLoader.load(GuardEngineFactory.class).iterator().next();
            GuardDiagnostics report = GuardDiagnostics.inspect(factory.create());
            Assert.assertEquals(
                    GuardDiagnostics.PolicySource.SYSTEM_PROPERTY,
                    report.providers().get(0).policySource());
            Assert.assertFalse(report.toString().contains(path));
            Assert.assertFalse(report.providers().get(0).toString().contains(path));
        } finally {
            if (previous == null) {
                System.clearProperty(AgtGuardProvider.POLICY_PATH_PROPERTY);
            } else {
                System.setProperty(AgtGuardProvider.POLICY_PATH_PROPERTY, previous);
            }
        }
    }

    @Test
    public void environmentSourceIsVerifiedInIsolatedProcess() throws Exception {
        Path log = Files.createTempFile("agt-diagnostic-test", ".log");
        Process process = null;
        try {
            ProcessBuilder command =
                    new ProcessBuilder(
                            new File(System.getProperty("java.home"), "bin/java").getPath(),
                            "-cp",
                            System.getProperty("java.class.path"),
                            AgtDiagnosticsTest.class.getName());
            command.environment()
                    .put(
                            AgtGuardProvider.POLICY_PATH_ENV,
                            Paths.get(getClass().getResource("/policy.yaml").toURI()).toString());
            command.redirectErrorStream(true).redirectOutput(log.toFile());
            process = command.start();
            Assert.assertTrue("child process should finish", process.waitFor(20, TimeUnit.SECONDS));
            Assert.assertEquals(
                    new String(Files.readAllBytes(log), StandardCharsets.UTF_8),
                    0,
                    process.exitValue());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(log);
        }
    }

    public static void main(String[] args) {
        System.clearProperty(AgtGuardProvider.POLICY_PATH_PROPERTY);
        GuardDiagnostics report = GuardDiagnostics.inspect(new AgtGuardEngineFactory().create());
        Assert.assertEquals(
                GuardDiagnostics.PolicySource.ENVIRONMENT,
                report.providers().get(0).policySource());
    }
}
