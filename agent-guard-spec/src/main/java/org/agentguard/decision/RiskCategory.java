package org.agentguard.decision;

/**
 * 风险大类。用于统计、告警路由和对外文案选择；细分类型请使用 {@link Finding#code()}。
 * 兼容性约定：只追加，不删除。
 */
public enum RiskCategory {

    /** 直接 / 间接 Prompt 注入。 */
    PROMPT_INJECTION,

    /** 越狱攻击。 */
    JAILBREAK,

    /** 内容安全：涉政、色情、暴力、违法等。 */
    CONTENT_SAFETY,

    /** 敏感个人信息（手机号、身份证、银行卡 ...）。 */
    SENSITIVE_DATA,

    /** 数据泄露：系统提示词、密钥、内部数据外泄。 */
    DATA_LEAKAGE,

    /** 权限 / 租户越权。 */
    PERMISSION,

    /** 合规策略。 */
    COMPLIANCE,

    /** 参数或格式校验不通过。 */
    VALIDATION,

    /** 危险操作 / 恶意代码。 */
    DANGEROUS_ACTION,

    /** Guard 自身执行异常（由 Engine 根据失败策略生成）。 */
    GUARD_ERROR,

    OTHER
}
