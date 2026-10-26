package org.agentguard.decision;

import org.agentguard.util.Immutables;

/**
 * Guard 的运行时策略：执行模式 + 失败策略。
 */
public final class GuardPolicy {

    /** 默认策略：强制执行 + 失败即拦截。 */
    public static final GuardPolicy DEFAULT = new GuardPolicy(EnforcementMode.ENFORCE, FailPolicy.FAIL_CLOSED);

    /** 观察模式 + 失败即放行，适合新规则灰度。 */
    public static final GuardPolicy OBSERVE_ONLY = new GuardPolicy(EnforcementMode.OBSERVE, FailPolicy.FAIL_OPEN);

    /** 禁用。 */
    public static final GuardPolicy DISABLED = new GuardPolicy(EnforcementMode.DISABLED, FailPolicy.FAIL_OPEN);

    private final EnforcementMode mode;
    private final FailPolicy failPolicy;

    private GuardPolicy(EnforcementMode mode, FailPolicy failPolicy) {
        this.mode = Immutables.requireNonNull(mode, "mode");
        this.failPolicy = Immutables.requireNonNull(failPolicy, "failPolicy");
    }

    public static GuardPolicy of(EnforcementMode mode, FailPolicy failPolicy) {
        return new GuardPolicy(mode, failPolicy);
    }

    public EnforcementMode mode() {
        return mode;
    }

    public FailPolicy failPolicy() {
        return failPolicy;
    }

    public GuardPolicy withMode(EnforcementMode newMode) {
        return new GuardPolicy(newMode, failPolicy);
    }

    public GuardPolicy withFailPolicy(FailPolicy newFailPolicy) {
        return new GuardPolicy(mode, newFailPolicy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GuardPolicy)) {
            return false;
        }
        GuardPolicy that = (GuardPolicy) o;
        return mode == that.mode && failPolicy == that.failPolicy;
    }

    @Override
    public int hashCode() {
        return 31 * mode.hashCode() + failPolicy.hashCode();
    }

    @Override
    public String toString() {
        return "GuardPolicy{mode=" + mode + ", failPolicy=" + failPolicy + '}';
    }
}
