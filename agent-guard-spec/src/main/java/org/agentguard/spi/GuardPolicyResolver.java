package org.agentguard.spi;

import org.agentguard.context.GuardContext;
import org.agentguard.decision.GuardPolicy;

/**
 * 策略解析 SPI —— 由平台（配置中心、租户管理）实现，在运行时动态决定每个 Guard 的
 * {@link GuardPolicy}（执行模式 / 失败策略），实现按租户灰度、一键降级为观察模式、紧急禁用等。
 *
 * <p>每次 Guard 执行前都会调用，实现必须足够快（建议本地缓存）。</p>
 */
public interface GuardPolicyResolver {

    /**
     * @return 生效策略；返回 null 表示使用 {@link Guard#defaultPolicy()}
     */
    GuardPolicy resolve(Guard<?> guard, GuardContext context);

    /** 直接使用各 Guard 的默认策略。 */
    static GuardPolicyResolver defaults() {
        return DefaultsHolder.INSTANCE;
    }

    /** JDK 8 接口不能有 private 成员，用嵌套类持有单例。 */
    final class DefaultsHolder {
        private DefaultsHolder() {
        }

        static final GuardPolicyResolver INSTANCE = new GuardPolicyResolver() {
            @Override
            public GuardPolicy resolve(Guard<?> guard, GuardContext context) {
                return guard.defaultPolicy();
            }
        };
    }
}
