package org.agentguard.exception;

import org.agentguard.GuardStage;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardAction;
import org.agentguard.util.Immutables;

import java.util.Collections;
import java.util.List;

/**
 * 护栏硬拦截异常：当任意阶段命中 BLOCK（且未被降级为观察模式）时抛出，用于强行终止 Agent 流程。
 *
 * <p>上层业务或框架全局异常处理器只需统一捕获此类即可。</p>
 */
public class GuardBlockedException extends GuardException {

    private static final long serialVersionUID = 1L;

    private final GuardStage stage;
    private final String reply;
    private final List<Finding> findings;

    public GuardBlockedException(GuardStage stage, String reply, List<Finding> findings) {
        super(buildMessage(stage, reply, findings));
        this.stage = stage;
        this.reply = reply;
        this.findings = findings == null ? Collections.<Finding>emptyList() : Immutables.list(findings);
    }

    private static String buildMessage(GuardStage stage, String reply, List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        sb.append("Agent process blocked at stage [").append(stage).append("]");
        if (reply != null && !reply.isEmpty()) {
            sb.append(": ").append(reply);
        }
        if (findings != null && !findings.isEmpty()) {
            sb.append(" (").append(findings.size()).append(" findings)");
        }
        return sb.toString();
    }

    /** 触发拦截的阶段。 */
    public GuardStage stage() {
        return stage;
    }

    /** 可对外呈现的拒绝回复或安全提示文案（如由 Guard 指定或适配器默认设定）。 */
    public String reply() {
        return reply;
    }

    /** 触发此拦截命中的所有违规发现列表。 */
    public List<Finding> findings() {
        return findings;
    }
}
