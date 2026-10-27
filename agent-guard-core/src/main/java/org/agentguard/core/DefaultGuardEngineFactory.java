package org.agentguard.core;

import org.agentguard.engine.GuardEngine;
import org.agentguard.spi.Guard;
import org.agentguard.spi.GuardEngineFactory;
import org.agentguard.spi.GuardProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 为采用参考引擎的 SDK 提供可复用的工厂，通过 Java SPI 加载 SDK 的 GuardProvider。
 *
 * <p>core 自身不注册本工厂。SDK 须显式在服务描述文件中注册本类及其 Provider，
 * 防止仅依赖 core 就自动启用一个没有检测规则的引擎。</p>
 */
public final class DefaultGuardEngineFactory implements GuardEngineFactory {

    @Override
    public GuardEngine create() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DefaultGuardEngineFactory.class.getClassLoader();
        }
        List<GuardProvider> providers = new ArrayList<GuardProvider>();
        for (GuardProvider provider : ServiceLoader.load(GuardProvider.class, loader)) {
            providers.add(provider);
        }
        Collections.sort(providers, new Comparator<GuardProvider>() {
            @Override
            public int compare(GuardProvider first, GuardProvider second) {
                return Integer.compare(first.order(), second.order());
            }
        });
        List<Guard<?>> guards = new ArrayList<Guard<?>>();
        for (GuardProvider provider : providers) {
            Collection<? extends Guard<?>> provided = provider.guards();
            if (provided == null) {
                throw new IllegalStateException("GuardProvider returned null guards: " + provider.getClass().getName());
            }
            guards.addAll(provided);
        }
        if (guards.isEmpty()) {
            throw new IllegalStateException("An auto-configured SDK must provide at least one Guard");
        }
        // 直接交给引擎校验 null、重复 id 和非法 stage，不能静默过滤无效规则。
        return new DefaultGuardEngine(guards, null, null, providers);
    }
}
