package org.agentguard.engine;

import org.agentguard.GuardStage;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardAction;
import org.agentguard.event.GuardRecord;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.util.Immutables;

import java.util.List;

/**
 * 某个阶段经过 Engine 编排拦截后的最终输出结果。
 *
 * @param <T> 阶段对应的 payload 类型
 */
public final class GuardOutcome<T> {

    private final GuardStage stage;
    private final GuardAction action;
    private final T payload;
    private final String reply;
    private final List<Finding> findings;
    private final List<GuardRecord> records;

    public GuardOutcome(GuardStage stage, GuardAction action, T payload,
                        String reply, List<Finding> findings, List<GuardRecord> records) {
        this.stage = Immutables.requireNonNull(stage, "stage");
        this.action = Immutables.requireNonNull(action, "action");
        this.payload = payload;
        this.reply = reply;
        this.findings = Immutables.list(findings);
        this.records = Immutables.list(records);
    }

    /** 当前所处的拦截阶段。 */
    public GuardStage stage() {
        return stage;
    }

    /** 该阶段的汇总决策动作。 */
    public GuardAction action() {
        return action;
    }

    /**
     * 当前有效 payload（如果被 MODIFY 过，则为改写后的 payload；若被 REJECT 或 BLOCK，则可能为 null 或原始对象）。
     */
    public T payload() {
        return payload;
    }

    /**
     * 获取有效 payload；违规 BLOCK 或 REJECT 抛出 GuardBlockedException，
     * 检测故障造成的 BLOCK 抛出 GuardExecutionException 并保留原因。
     *
     * <p>简便用法：适配器如果不打算单独处理软拒绝 reply，可直接调用此方法快速终止。</p>
     *
     * @return 经过处理后的有效 payload
     * @throws GuardExecutionException 当检测故障按 FAIL_CLOSED 阻断时抛出
     * @throws GuardBlockedException 当动作不是放行或改写时抛出
     */
    public T payloadOrThrow() throws GuardBlockedException {
        if (isBlocked()) {
            for (GuardRecord record : records) {
                if (record.effectiveAction() == GuardAction.BLOCK && record.error() != null) {
                    throw new GuardExecutionException(record.guardId(), stage,
                            "安全检测服务异常", record.error());
                }
            }
        }
        if (!isProceed()) {
            throw new GuardBlockedException(stage, reply, findings);
        }
        return payload;
    }

    /** 软拒绝或硬拦截时给出的提示/回复文案。 */
    public String reply() {
        return reply;
    }

    /** 命中且保留的所有违规发现（包含放行观察 OBSERVE 的记录）。 */
    public List<Finding> findings() {
        return findings;
    }

    /** 该阶段各 Guard 的明细执行记录。 */
    public List<GuardRecord> records() {
        return records;
    }

    /** 是否可以继续执行（PASS, OBSERVE, MODIFY）。 */
    public boolean isProceed() {
        return action.isProceed();
    }

    /** 是否被硬拦截（流程必须被强行终止）。 */
    public boolean isBlocked() {
        return action == GuardAction.BLOCK;
    }

    /** 是否被软拒绝（跳过当前步骤，由 reply 代替输出）。 */
    public boolean isRejected() {
        return action == GuardAction.REJECT;
    }

    /** 是否发生了内容改写（脱敏/掩码）。 */
    public boolean isModified() {
        return action == GuardAction.MODIFY;
    }

    /** 是否存在命中但放行观察的记录。 */
    public boolean hasObservedFindings() {
        if (findings.isEmpty()) {
            return false;
        }
        for (GuardRecord record : records) {
            if (record.effectiveAction() == GuardAction.OBSERVE) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "GuardOutcome{stage=" + stage +
                ", action=" + action +
                ", hasPayload=" + (payload != null) +
                ", reply='" + reply + '\'' +
                ", findings=" + findings.size() +
                '}';
    }
}
