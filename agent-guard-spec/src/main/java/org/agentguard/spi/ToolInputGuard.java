package org.agentguard.spi;

import org.agentguard.GuardStage;
import org.agentguard.model.ToolCall;

/** TOOL_INPUT 阶段护栏：工具调用前的租户、权限、参数校验。 */
public interface ToolInputGuard extends Guard<ToolCall> {

    @Override
    default GuardStage stage() {
        return GuardStage.TOOL_INPUT;
    }
}
