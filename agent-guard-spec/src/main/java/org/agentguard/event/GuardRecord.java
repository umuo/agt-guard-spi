package org.agentguard.event;

import org.agentguard.GuardStage;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardAction;
import org.agentguard.util.Immutables;

import java.util.List;

/**
 * 单个 Guard 执行的明细审计记录。
 */
public final class GuardRecord {

    private final String guardId;
    private final GuardStage stage;
    private final GuardAction decidedAction;
    private final GuardAction effectiveAction;
    private final long durationNanos;
    private final List<Finding> findings;
    private final Throwable error;

    public GuardRecord(String guardId, GuardStage stage, GuardAction decidedAction,
                       GuardAction effectiveAction, long durationNanos,
                       List<Finding> findings, Throwable error) {
        this.guardId = Immutables.requireNonNull(guardId, "guardId");
        this.stage = Immutables.requireNonNull(stage, "stage");
        if (decidedAction == null && error == null) {
            throw new IllegalArgumentException("decidedAction may be null only for an execution failure");
        }
        this.decidedAction = decidedAction;
        this.effectiveAction = Immutables.requireNonNull(effectiveAction, "effectiveAction");
        this.durationNanos = durationNanos;
        this.findings = Immutables.list(findings);
        this.error = error;
    }

    public String guardId() {
        return guardId;
    }

    public GuardStage stage() {
        return stage;
    }

    /** Guard 本身返回的判定动作（未经策略覆盖）；检测故障没有决议，返回 null。 */
    public GuardAction decidedAction() {
        return decidedAction;
    }

    /** 结合 EnforcementMode 后的最终动作（例如若处于观察模式，BLOCK 会被降级为 OBSERVE）。 */
    public GuardAction effectiveAction() {
        return effectiveAction;
    }

    /** 执行耗时（纳秒）。 */
    public long durationNanos() {
        return durationNanos;
    }

    /** 执行耗时（毫秒）。 */
    public double durationMs() {
        return durationNanos / 1_000_000.0;
    }

    /** 该 Guard 命中的风险列表。 */
    public List<Finding> findings() {
        return findings;
    }

    /** 如果执行异常抛出，则记录该异常；正常执行时为 null。 */
    public Throwable error() {
        return error;
    }

    @Override
    public String toString() {
        return "GuardRecord{guardId='" + guardId + '\'' +
                ", stage=" + stage +
                ", decidedAction=" + decidedAction +
                ", effectiveAction=" + effectiveAction +
                ", durationMs=" + String.format("%.2f", durationMs()) +
                ", findings=" + findings.size() +
                (error != null ? ", error=" + error.getMessage() : "") +
                '}';
    }
}
