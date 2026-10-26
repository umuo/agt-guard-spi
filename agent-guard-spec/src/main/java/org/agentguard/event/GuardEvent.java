package org.agentguard.event;

import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardAction;
import org.agentguard.util.Immutables;

import java.util.List;

/**
 * 阶段级别的护栏聚合审计事件。在某个阶段的所有 Guard 执行完毕（或被短路拦截）后触发。
 */
public final class GuardEvent {

    private final GuardContext context;
    private final GuardStage stage;
    private final GuardAction overallAction;
    private final List<GuardRecord> records;
    private final List<Finding> allFindings;
    private final long totalDurationNanos;

    public GuardEvent(GuardContext context, GuardStage stage, GuardAction overallAction,
                      List<GuardRecord> records, List<Finding> allFindings, long totalDurationNanos) {
        this.context = Immutables.requireNonNull(context, "context");
        this.stage = Immutables.requireNonNull(stage, "stage");
        this.overallAction = Immutables.requireNonNull(overallAction, "overallAction");
        this.records = Immutables.list(records);
        this.allFindings = Immutables.list(allFindings);
        this.totalDurationNanos = totalDurationNanos;
    }

    public GuardContext context() {
        return context;
    }

    public GuardStage stage() {
        return stage;
    }

    /** 该阶段汇总决定的全局动作。 */
    public GuardAction overallAction() {
        return overallAction;
    }

    /** 所有执行过的 Guard 的详细审计记录。 */
    public List<GuardRecord> records() {
        return records;
    }

    /** 汇总的所有风险发现（包含仅观察 OBSERVE 的发现）。 */
    public List<Finding> allFindings() {
        return allFindings;
    }

    public long totalDurationNanos() {
        return totalDurationNanos;
    }

    public double totalDurationMs() {
        return totalDurationNanos / 1_000_000.0;
    }

    @Override
    public String toString() {
        return "GuardEvent{stage=" + stage +
                ", traceId=" + context.traceId() +
                ", tenantId=" + context.tenantId() +
                ", action=" + overallAction +
                ", records=" + records.size() +
                ", findings=" + allFindings.size() +
                ", durationMs=" + String.format("%.2f", totalDurationMs()) +
                '}';
    }
}
