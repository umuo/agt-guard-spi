package org.agentguard.spi;

import java.util.Collection;

import org.agentguard.diagnostics.GuardDiagnostics.PolicySource;

/**
 * Guard 提供者 —— 安全 SDK 的注册入口。
 *
 * <p>通过 JDK {@link java.util.ServiceLoader} 发现：在 SDK jar 中添加文件
 * {@code META-INF/services/org.agentguard.spi.GuardProvider}，内容为实现类全名。
 * Spring 等容器环境也可以直接把 Provider / Guard 注册为 Bean，由适配层收集。</p>
 *
 * <p>实现类必须提供 public 无参构造器（ServiceLoader 要求）。
 * 框架自动装配的入口是 GuardEngineFactory；仅注册本接口不会自动启用引擎。
 * 采用参考引擎的 SDK 可同时注册 core 提供的 DefaultGuardEngineFactory。</p>
 */
public interface GuardProvider {

    /** Provider 名称，如 {@code acme-security-sdk}。 */
    String name();

    /** 多个 Provider 的加载顺序，越小越先；仅影响注册顺序，Guard 执行顺序由 {@link Guard#order()} 决定。 */
    default int order() {
        return 0;
    }

    /**
     * 返回策略来源类别，不返回文件路径或策略内容；第三方实现默认未知。
     */
    default PolicySource policySource() {
        return PolicySource.UNKNOWN;
    }

    /** 本 Provider 提供的全部 Guard。每次调用应返回相同的实例集合。 */
    Collection<? extends Guard<?>> guards();
}
