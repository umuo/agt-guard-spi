package org.agentguard.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.spi.Guard;
import org.agentguard.spi.GuardProvider;
import org.agentguard.streaming.GuardStream;
import org.agentguard.streaming.GuardStreamOptions;
import org.agentguard.streaming.StreamingGuardProvider;

/**
 * 为参考引擎选择完整缓存或经过验证的单 Provider 窗口处理器。
 * 不允许额外输出 Guard 或动态策略被窗口路径静默绕过。
 */
final class GuardStreamingSupport {
    private final List<Guard<?>> guards;
    private final List<GuardProvider> providers;
    private final boolean defaultPolicy;

    GuardStreamingSupport(List<Guard<?>> guards, List<GuardProvider> providers, boolean defaultPolicy) {
        this.guards = guards == null ? Collections.emptyList() : new ArrayList<>(guards);
        this.providers = providers == null ? Collections.emptyList() : new ArrayList<>(providers);
        this.defaultPolicy = defaultPolicy;
    }

    void validate(GuardStreamOptions options) {
        Objects.requireNonNull(options, "options");
        if (options.mode() == GuardStreamOptions.Mode.WINDOW) {
            windowProvider().validateWindow(options);
        }
    }

    GuardStream open(GuardStreamOptions options, GuardContext context,
            Consumer<String> output, Consumer<Throwable> failure) {
        validate(options);
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(failure, "failure");
        if (options.mode() == GuardStreamOptions.Mode.STRICT) {
            return new StrictGuardStream(options.maxBufferedCharacters(), output);
        }
        return windowProvider().openWindow(options, context, output, failure);
    }

    private StreamingGuardProvider windowProvider() {
        if (!defaultPolicy) {
            throw new IllegalArgumentException("Window streaming does not support a dynamic policy resolver; use strict mode");
        }
        StreamingGuardProvider selected = null;
        for (GuardProvider provider : providers) {
            if (provider instanceof StreamingGuardProvider) {
                if (selected != null) {
                    throw new IllegalArgumentException("Window streaming requires a single streaming Provider; use strict mode");
                }
                selected = (StreamingGuardProvider) provider;
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("No streaming Provider; use strict mode");
        }
        Map<Guard<?>, Boolean> identity = new IdentityHashMap<>();
        for (Guard<?> guard : selected.guards()) {
            identity.put(guard, Boolean.TRUE);
        }
        for (Guard<?> guard : guards) {
            if ((guard.stage() == GuardStage.MODEL_OUTPUT || guard.stage() == GuardStage.AGENT_OUTPUT)
                    && !identity.containsKey(guard)) {
                throw new IllegalArgumentException("Additional output Guard is not covered by window streaming; use strict mode");
            }
        }
        return selected;
    }
}
