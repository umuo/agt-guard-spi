package org.agentguard.agt;

import org.agentguard.core.DefaultGuardEngineFactory;
import org.agentguard.engine.GuardEngine;
import org.agentguard.spi.GuardEngineFactory;

/**
 * 发布 AGT SDK 的自动装配入口，并通过参考工厂组合已注册的安全 Provider。
 * 配置读取及策略校验发生在 create 阶段，构造器不创建外部资源。
 */
public final class AgtGuardEngineFactory implements GuardEngineFactory {
    @Override
    public GuardEngine create() {
        return new DefaultGuardEngineFactory().create();
    }
}
