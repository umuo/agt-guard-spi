package org.agentguard.spi;

import org.agentguard.GuardStage;
import org.agentguard.model.ModelRequest;

/** MODEL_INPUT 阶段护栏：调用 LLM 前检测完整 Prompt（含 RAG 片段、工具结果、可见工具列表）。 */
public interface ModelInputGuard extends Guard<ModelRequest> {

    @Override
    default GuardStage stage() {
        return GuardStage.MODEL_INPUT;
    }
}
