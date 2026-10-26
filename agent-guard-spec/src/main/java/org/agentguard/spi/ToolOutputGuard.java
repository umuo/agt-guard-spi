package org.agentguard.spi;

import org.agentguard.GuardStage;
import org.agentguard.model.ToolResult;

/** TOOL_OUTPUT 阶段护栏：工具结果脱敏、间接 Prompt 注入检测。 */
public interface ToolOutputGuard extends Guard<ToolResult> {

    @Override
    default GuardStage stage() {
        return GuardStage.TOOL_OUTPUT;
    }
}
