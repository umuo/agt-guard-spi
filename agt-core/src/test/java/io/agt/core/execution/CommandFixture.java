package io.agt.core.execution;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * 本机受信任测试程序。
 *
 * <p>用于真实子进程验证，不读取外部服务或真实凭据。
 */
public final class CommandFixture {
    public static void main(String[] args) throws Exception {
        Path marker = Paths.get(args[1]);
        Path pending = marker.resolveSibling(marker.getFileName() + ".pending");
        Files.write(pending, processId().getBytes(StandardCharsets.UTF_8));
        Files.move(
                pending,
                marker,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        switch (args[0]) {
            case "echo":
                System.out.print("secret-result");
                break;
            case "environment":
                System.out.print(
                        System.getenv().keySet().stream()
                                        .allMatch(
                                                key ->
                                                        key.equals("__CF_USER_TEXT_ENCODING")
                                                                || key.equals(
                                                                        "JAVA_MAIN_CLASS_"
                                                                                + processId()))
                                ? "empty"
                                : "inherited");
                break;
            case "timeout":
                Thread.sleep(60000);
                break;
            case "overflow":
                {
                    char[] output = new char[100000];
                    Arrays.fill(output, 'x');
                    System.out.print(output);
                    break;
                }
            case "exit":
                System.exit(3);
                break;
            case "invalid":
                System.out.write(new byte[] {(byte) 0xff});
                break;
            default:
                System.out.print(args[0]);
        }
    }

    private static String processId() {
        return ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0];
    }
}
