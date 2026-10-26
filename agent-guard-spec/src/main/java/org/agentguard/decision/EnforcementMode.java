package org.agentguard.decision;

/**
 * 执行模式：决定 Guard 的判定结果是否真正生效。由 {@link GuardPolicy} 承载，
 * 可按租户 / Agent / 工具等维度动态下发，用于新规则灰度上线。
 */
public enum EnforcementMode {

    /** 强制执行：判定结果原样生效。 */
    ENFORCE,

    /**
     * 观察模式（dry-run）：Guard 照常执行，但 MODIFY / REJECT / BLOCK 一律降级为 OBSERVE，
     * 不影响流程；原始判定保留在 GuardRecord#decidedAction 中，便于评估"如果开启会拦截多少"。
     */
    OBSERVE,

    /** 禁用：不执行该 Guard。 */
    DISABLED
}
