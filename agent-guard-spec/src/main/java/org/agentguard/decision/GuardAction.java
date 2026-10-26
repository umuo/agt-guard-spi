package org.agentguard.decision;

import org.agentguard.GuardStage;

/**
 * 护栏动作，按严重程度从低到高排列：PASS &lt; OBSERVE &lt; MODIFY &lt; REJECT &lt; BLOCK。
 *
 * <table border="1">
 *   <caption>各动作对流程的影响</caption>
 *   <tr><th>动作</th><th>是否命中风险</th><th>流程</th><th>payload</th></tr>
 *   <tr><td>PASS</td><td>否</td><td>继续</td><td>原样</td></tr>
 *   <tr><td>OBSERVE</td><td>是</td><td>继续（放行观察，仅记录/告警）</td><td>原样</td></tr>
 *   <tr><td>MODIFY</td><td>是</td><td>继续</td><td>改写后（脱敏 / 掩码）</td></tr>
 *   <tr><td>REJECT</td><td>是</td><td>跳过当前步骤，用 reply 作为替代结果后继续（软拦截）</td><td>丢弃</td></tr>
 *   <tr><td>BLOCK</td><td>是</td><td>终止整个 Agent 流程，抛 GuardBlockedException</td><td>丢弃</td></tr>
 * </table>
 */
public enum GuardAction {

    /** 未命中风险，放行。 */
    PASS(0),

    /** 命中风险但放行：只记录、上报、告警，不影响流程。用于低置信度命中或规则灰度。 */
    OBSERVE(1),

    /** 命中风险，改写 payload 后放行（脱敏、掩码、删除敏感片段、移除危险工具等）。 */
    MODIFY(2),

    /**
     * 软拦截：丢弃当前步骤的真实结果，以 reply 作为替代结果，流程继续。各阶段语义：
     * <ul>
     *   <li>{@link GuardStage#AGENT_INPUT}：不进入 Agent，直接以 reply 答复用户；</li>
     *   <li>{@link GuardStage#MODEL_INPUT}：不调用 LLM，以 reply 作为本轮 LLM 回复（无 tool_calls，即结束循环）；</li>
     *   <li>{@link GuardStage#MODEL_OUTPUT}：丢弃 LLM 回复（含 tool_calls），以 reply 替换；</li>
     *   <li>{@link GuardStage#TOOL_INPUT}：不执行工具，以 reply 作为工具结果回传 LLM；</li>
     *   <li>{@link GuardStage#TOOL_OUTPUT}：丢弃工具结果，以 reply 作为工具结果回传 LLM；</li>
     *   <li>{@link GuardStage#AGENT_OUTPUT}：以 reply 替换最终答复。</li>
     * </ul>
     * 适配层若无法实现某阶段的 REJECT 语义，必须按 BLOCK 处理，不允许静默放行。
     */
    REJECT(3),

    /** 硬拦截：终止整个 Agent 流程。 */
    BLOCK(4);

    private final int severity;

    GuardAction(int severity) {
        this.severity = severity;
    }

    public int severity() {
        return severity;
    }

    public boolean isMoreSevereThan(GuardAction other) {
        return this.severity > other.severity;
    }

    /** 是否命中风险（非 PASS）。 */
    public boolean isHit() {
        return this != PASS;
    }

    /** 流程是否可以继续使用（可能被改写过的）payload。 */
    public boolean isProceed() {
        return this == PASS || this == OBSERVE || this == MODIFY;
    }

    public static GuardAction max(GuardAction a, GuardAction b) {
        return a.severity >= b.severity ? a : b;
    }
}
