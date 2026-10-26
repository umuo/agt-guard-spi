package org.agentguard.spi;

import org.agentguard.GuardStage;
import org.agentguard.model.AgentInput;

/** AGENT_INPUT 阶段护栏：用户输入安全检测（Prompt 注入、越狱、内容安全 ...）。 */
public interface AgentInputGuard extends Guard<AgentInput> {

    @Override
    default GuardStage stage() {
        return GuardStage.AGENT_INPUT;
    }
}
