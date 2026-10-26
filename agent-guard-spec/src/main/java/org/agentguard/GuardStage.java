package org.agentguard;

import org.agentguard.model.AgentInput;
import org.agentguard.model.AgentOutput;
import org.agentguard.model.ModelRequest;
import org.agentguard.model.ModelResponse;
import org.agentguard.model.ToolCall;
import org.agentguard.model.ToolResult;

/**
 * 护栏拦截阶段。每个阶段绑定一种固定的 payload 类型。
 *
 * <pre>
 *  用户输入 ─▶ AGENT_INPUT
 *               │
 *               ▼   ┌──────────── ReAct 循环（可能多轮）────────────┐
 *            MODEL_INPUT ─▶ LLM ─▶ MODEL_OUTPUT                      │
 *               │                     │ 有 tool_calls               │
 *               │                     ▼                             │
 *               │              TOOL_INPUT ─▶ Tool ─▶ TOOL_OUTPUT ───┘
 *               ▼
 *           AGENT_OUTPUT ─▶ 返回用户
 * </pre>
 *
 * <p>兼容性约定：后续版本只会<b>追加</b>枚举常量，不会删除或改名。
 * 适配层在 switch 本枚举时必须保留 default 分支。</p>
 */
public enum GuardStage {

    /** 用户原始输入进入 Agent 之前。每次请求触发 1 次。 */
    AGENT_INPUT(AgentInput.class),

    /** 每次调用 LLM 之前。ReAct 循环中每轮触发 1 次。 */
    MODEL_INPUT(ModelRequest.class),

    /** 每次 LLM 返回之后（包括携带 tool_calls 的中间回复）。 */
    MODEL_OUTPUT(ModelResponse.class),

    /** 每次工具执行之前：租户、权限、参数校验。 */
    TOOL_INPUT(ToolCall.class),

    /** 每次工具执行之后：结果脱敏、间接 Prompt 注入检测。 */
    TOOL_OUTPUT(ToolResult.class),

    /** Agent 最终答复返回给用户之前。每次请求触发 1 次。 */
    AGENT_OUTPUT(AgentOutput.class);

    private final Class<?> payloadType;

    GuardStage(Class<?> payloadType) {
        this.payloadType = payloadType;
    }

    /** 该阶段对应的 payload 类型。 */
    public Class<?> payloadType() {
        return payloadType;
    }
}
