package org.agentguard.core;

import org.agentguard.engine.GuardEngine;
import org.agentguard.event.GuardEventListener;
import org.agentguard.spi.Guard;
import org.agentguard.spi.GuardPolicyResolver;
import org.agentguard.spi.GuardProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * {@link GuardEngine} 实例构建器。
 */
public class GuardEngineBuilder {

    private final List<GuardProvider> providers = new ArrayList<GuardProvider>();
    private final List<Guard<?>> guards = new ArrayList<Guard<?>>();
    private final List<GuardEventListener> listeners = new ArrayList<GuardEventListener>();
    private GuardPolicyResolver policyResolver = GuardPolicyResolver.defaults();

    public GuardEngineBuilder addGuard(Guard<?> guard) {
        if (guard != null) {
            this.guards.add(guard);
        }
        return this;
    }

    public GuardEngineBuilder addGuards(Collection<? extends Guard<?>> guards) {
        if (guards != null) {
            for (Guard<?> g : guards) {
                if (g != null) {
                    this.guards.add(g);
                }
            }
        }
        return this;
    }

    public GuardEngineBuilder addProvider(GuardProvider provider) {
        if (provider != null) {
            providers.add(provider);
            Collection<? extends Guard<?>> providedGuards = provider.guards();
            addGuards(providedGuards);
        }
        return this;
    }

    /**
     * 使用 JDK {@link ServiceLoader} 自动扫描 classpath 下注册的 {@link GuardProvider}。
     */
    public GuardEngineBuilder loadProviders() {
        return loadProviders(Thread.currentThread().getContextClassLoader());
    }

    public GuardEngineBuilder loadProviders(ClassLoader classLoader) {
        ServiceLoader<GuardProvider> loader = ServiceLoader.load(GuardProvider.class, classLoader);
        List<GuardProvider> providers = new ArrayList<GuardProvider>();
        for (GuardProvider provider : loader) {
            providers.add(provider);
        }
        // 按 provider 的 order 排序加载
        Collections.sort(providers, new Comparator<GuardProvider>() {
            @Override
            public int compare(GuardProvider p1, GuardProvider p2) {
                return Integer.compare(p1.order(), p2.order());
            }
        });
        for (GuardProvider provider : providers) {
            addProvider(provider);
        }
        return this;
    }

    public GuardEngineBuilder addListener(GuardEventListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
        return this;
    }

    public GuardEngineBuilder policyResolver(GuardPolicyResolver policyResolver) {
        if (policyResolver != null) {
            this.policyResolver = policyResolver;
        }
        return this;
    }

    public GuardEngine build() {
        return new DefaultGuardEngine(guards, policyResolver, listeners, providers);
    }
}
