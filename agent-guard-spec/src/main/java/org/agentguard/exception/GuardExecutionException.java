package org.agentguard.exception;

import org.agentguard.GuardStage;

/**
 * 护栏执行异常：当 Guard 内部发生未捕获异常或超时，且策略为 FAIL_CLOSED 时抛出。
 */
public class GuardExecutionException extends GuardException {

    private static final long serialVersionUID = 1L;

    private final String guardId;
    private final GuardStage stage;

    public GuardExecutionException(String guardId, GuardStage stage, String message, Throwable cause) {
        super("Guard [" + guardId + "] failed at stage [" + stage + "]: " + message, cause);
        this.guardId = guardId;
        this.stage = stage;
    }

    public String guardId() {
        return guardId;
    }

    public GuardStage stage() {
        return stage;
    }
}
