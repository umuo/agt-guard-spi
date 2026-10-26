package io.agt.core.execution;

import java.lang.reflect.Method;
import java.util.stream.Stream;

/**
 * 隔离 Java 9 进程句柄扩展，在 Java 8 上保持主进程清理能力。
 * 后代清理始终属于尽力而为；需要进程组或沙箱保证时必须提供专用执行后端。
 */
public final class ProcessCleanup {
    private ProcessCleanup() {}

    public static boolean supportsDescendants() {
        try {
            Process.class.getMethod("descendants");
            Class.forName("java.lang.ProcessHandle").getMethod("destroyForcibly");
            return true;
        } catch (ReflectiveOperationException | SecurityException unavailable) {
            return false;
        }
    }

    static void descendants(Process process) {
        if (!supportsDescendants()) {
            return;
        }
        try {
            Method descendants = Process.class.getMethod("descendants");
            Method destroy = Class.forName("java.lang.ProcessHandle").getMethod("destroyForcibly");
            try (Stream<?> handles = (Stream<?>) descendants.invoke(process)) {
                handles.forEach(
                        handle -> {
                            try {
                                destroy.invoke(handle);
                            } catch (ReflectiveOperationException | RuntimeException ignored) {
                                // 后代清理失败不能阻止主进程独立清理。
                            }
                        });
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 宿主可能禁止扩展 API，主进程仍由调用方关闭。
        }
    }
}
