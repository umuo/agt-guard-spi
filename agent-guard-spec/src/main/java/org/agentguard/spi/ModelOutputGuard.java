package org.agentguard.spi;

import org.agentguard.GuardStage;
import org.agentguard.model.ModelResponse;

/** MODEL_OUTPUT 阶段护栏：LLM 输出检测（敏感内容、系统提示词泄露、可疑 tool_calls ...）。 */
public interface ModelOutputGuard extends Guard<ModelResponse> {

    @Override
    default GuardStage stage() {
        return GuardStage.MODEL_OUTPUT;
    }
}
