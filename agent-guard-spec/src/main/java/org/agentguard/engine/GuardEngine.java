package org.agentguard.engine;

import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.model.AgentInput;
import org.agentguard.model.AgentOutput;
import org.agentguard.model.ModelRequest;
import org.agentguard.model.ModelResponse;
import org.agentguard.model.ToolCall;
import org.agentguard.model.ToolResult;

/**
 * 护栏引擎门面接口 —— 上层 Agent 框架适配器（Adapter）唯一需要调用的标准入口。
 *
 * <p>无论底层注册了多少个 SDK 或 Guard，适配器都只需要调用对应阶段的方法：</p>
 * <pre>{@code
 * GuardOutcome<AgentInput> outcome = engine.checkAgentInput(input, context);
 * AgentInput safeInput = outcome.payloadOrThrow(); // 若违规直接抛 GuardBlockedException 终止流程
 * }</pre>
 */
public interface GuardEngine {

    /**
     * 通用拦截入口。
     *
     * @param stage   阶段
     * @param payload 阶段 payload
     * @param context 护栏上下文
     * @param <T>     payload 类型
     * @return 编排执行结果
     */
    <T> GuardOutcome<T> check(GuardStage stage, T payload, GuardContext context);

    /** AGENT_INPUT 阶段：Agent 接收用户输入前检测。 */
    default GuardOutcome<AgentInput> checkAgentInput(AgentInput input, GuardContext context) {
        return check(GuardStage.AGENT_INPUT, input, context);
    }

    /** MODEL_INPUT 阶段：调用 LLM 前检测提示词与工具。 */
    default GuardOutcome<ModelRequest> checkModelInput(ModelRequest request, GuardContext context) {
        return check(GuardStage.MODEL_INPUT, request, context);
    }

    /** MODEL_OUTPUT 阶段：LLM 返回结果后检测。 */
    default GuardOutcome<ModelResponse> checkModelOutput(ModelResponse response, GuardContext context) {
        return check(GuardStage.MODEL_OUTPUT, response, context);
    }

    /** TOOL_INPUT 阶段：工具调用前检测（租户校验、权限校验、参数格式检测）。 */
    default GuardOutcome<ToolCall> checkToolInput(ToolCall toolCall, GuardContext context) {
        return check(GuardStage.TOOL_INPUT, toolCall, context);
    }

    /** TOOL_OUTPUT 阶段：工具执行结果返回后检测（脱敏、间接注入排查）。 */
    default GuardOutcome<ToolResult> checkToolOutput(ToolResult toolResult, GuardContext context) {
        return check(GuardStage.TOOL_OUTPUT, toolResult, context);
    }

    /** AGENT_OUTPUT 阶段：Agent 最终答复返回用户前检测。 */
    default GuardOutcome<AgentOutput> checkAgentOutput(AgentOutput output, GuardContext context) {
        return check(GuardStage.AGENT_OUTPUT, output, context);
    }
}
