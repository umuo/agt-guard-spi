package org.agentguard.streaming;

import java.util.function.Consumer;
import org.agentguard.context.GuardContext;
import org.agentguard.spi.GuardProvider;

/**
 * 提供窗口检查的可选 Provider 能力，承担自身全部输出规则的兼容性验证。
 * 参考引擎仅在输出 Guard 均归属该 Provider 且无动态策略时委托窗口处理。
 */
public interface StreamingGuardProvider extends GuardProvider {
    void validateWindow(GuardStreamOptions options);

    GuardStream openWindow(GuardStreamOptions options, GuardContext context,
            Consumer<String> output, Consumer<Throwable> failure);
}
