package org.agentguard.decision;

import org.agentguard.util.Immutables;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 单个 Guard 的判定结果。只能通过静态工厂方法创建。
 *
 * <pre>{@code
 * return GuardDecision.pass();
 * return GuardDecision.observe(finding);                       // 放行观察
 * return GuardDecision.modify(payload.withText(masked), finding); // 脱敏后放行
 * return GuardDecision.reject("该工具暂不可用", finding);         // 软拦截
 * return GuardDecision.block("请求包含违规内容", finding);         // 终止流程
 * }</pre>
 *
 * @param <T> 阶段 payload 类型
 */
public final class GuardDecision<T> {

    private static final GuardDecision<Object> PASS =
            new GuardDecision<Object>(GuardAction.PASS, null, null, Collections.<Finding>emptyList());

    private final GuardAction action;
    private final T payload;
    private final String reply;
    private final List<Finding> findings;

    private GuardDecision(GuardAction action, T payload, String reply, List<Finding> findings) {
        this.action = action;
        this.payload = payload;
        this.reply = reply;
        this.findings = Immutables.list(findings);
    }

    /** 放行。 */
    @SuppressWarnings("unchecked")
    public static <T> GuardDecision<T> pass() {
        return (GuardDecision<T>) PASS;
    }

    /** 命中但放行观察。 */
    public static <T> GuardDecision<T> observe(Finding... findings) {
        return observe(asList(findings));
    }

    public static <T> GuardDecision<T> observe(List<Finding> findings) {
        requireFindings(findings, GuardAction.OBSERVE);
        return new GuardDecision<T>(GuardAction.OBSERVE, null, null, findings);
    }

    /** 改写 payload 后放行。modifiedPayload 必须是同阶段的 payload 类型。 */
    public static <T> GuardDecision<T> modify(T modifiedPayload, Finding... findings) {
        return modify(modifiedPayload, asList(findings));
    }

    public static <T> GuardDecision<T> modify(T modifiedPayload, List<Finding> findings) {
        Immutables.requireNonNull(modifiedPayload, "modifiedPayload");
        requireFindings(findings, GuardAction.MODIFY);
        return new GuardDecision<T>(GuardAction.MODIFY, modifiedPayload, null, findings);
    }

    /** 软拦截：以 reply 作为当前步骤的替代结果。 */
    public static <T> GuardDecision<T> reject(String reply, Finding... findings) {
        return reject(reply, asList(findings));
    }

    public static <T> GuardDecision<T> reject(String reply, List<Finding> findings) {
        Immutables.requireNonNull(reply, "reply");
        requireFindings(findings, GuardAction.REJECT);
        return new GuardDecision<T>(GuardAction.REJECT, null, reply, findings);
    }

    /** 硬拦截：终止流程。reply 为可对外展示的提示，可为 null（由适配层使用默认文案）。 */
    public static <T> GuardDecision<T> block(String reply, Finding... findings) {
        return block(reply, asList(findings));
    }

    public static <T> GuardDecision<T> block(String reply, List<Finding> findings) {
        requireFindings(findings, GuardAction.BLOCK);
        return new GuardDecision<T>(GuardAction.BLOCK, null, reply, findings);
    }

    public static <T> GuardDecision<T> block(Finding... findings) {
        return block(null, asList(findings));
    }

    public GuardAction action() {
        return action;
    }

    /** MODIFY 时为改写后的 payload，其余为 null。 */
    public T payload() {
        return payload;
    }

    /** REJECT / BLOCK 时对外展示的文案。 */
    public String reply() {
        return reply;
    }

    public List<Finding> findings() {
        return findings;
    }

    private static List<Finding> asList(Finding[] findings) {
        return findings == null ? Collections.<Finding>emptyList() : Arrays.asList(findings);
    }

    private static void requireFindings(List<Finding> findings, GuardAction action) {
        if (findings == null || findings.isEmpty()) {
            throw new IllegalArgumentException(action + " decision requires at least one finding");
        }
        for (Finding f : findings) {
            if (f == null) {
                throw new IllegalArgumentException("finding must not be null");
            }
        }
    }

    @Override
    public String toString() {
        return "GuardDecision{action=" + action + ", findings=" + findings + '}';
    }
}
