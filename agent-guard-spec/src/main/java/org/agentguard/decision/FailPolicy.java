package org.agentguard.decision;

/**
 * 失败策略：Guard 自身抛出异常（SDK 故障、规则加载失败等）时的处理方式。
 */
public enum FailPolicy {

    /** 失败即拦截（安全优先）：等价于 BLOCK，finding 类别为 {@link RiskCategory#GUARD_ERROR}。 */
    FAIL_CLOSED,

    /** 失败即放行（可用性优先）：等价于 OBSERVE，finding 类别为 {@link RiskCategory#GUARD_ERROR}，便于监控。 */
    FAIL_OPEN
}
