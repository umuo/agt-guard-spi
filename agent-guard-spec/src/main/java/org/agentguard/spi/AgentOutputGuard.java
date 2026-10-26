package org.agentguard.spi;

import org.agentguard.GuardStage;
import org.agentguard.model.AgentOutput;

/** AGENT_OUTPUT 阶段护栏：最终答复检测。 */
public interface AgentOutputGuard extends Guard<AgentOutput> {

    @Override
    default GuardStage stage() {
        return GuardStage.AGENT_OUTPUT;
    }
}
