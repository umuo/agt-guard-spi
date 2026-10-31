package guide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.decision.GuardAction;
import org.agentguard.engine.GuardEngine;
import org.agentguard.engine.GuardOutcome;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.model.AgentInput;
import org.agentguard.model.AgentOutput;
import org.agentguard.model.GuardMessage;
import org.agentguard.model.ModelRequest;
import org.agentguard.model.ModelResponse;
import org.agentguard.model.Role;
import org.agentguard.model.ToolCall;
import org.agentguard.model.ToolDefinition;
import org.agentguard.model.ToolResult;

/**
 * 仅依赖 spec 的接入骨架冒烟验证，不访问模型或真实工具。
 * 验证六阶段顺序、改写应用、工具过滤和工具阻断前没有副作用。
 */
public final class FrameworkGuardAdapterSmoke {
    private FrameworkGuardAdapterSmoke() {
    }

    public static void main(String[] args) {
        List<GuardStage> stages = new ArrayList<>();
        FrameworkGuardAdapter adapter = new FrameworkGuardAdapter(engine(stages, false, false));
        FrameworkGuardAdapter.RequestSession session = session();
        AgentInput input = adapter.beforeAgent(AgentInput.of("PRIVATE"), session);
        require("MASKED".equals(input.text()), "Input modification must be applied");
        FrameworkGuardAdapter.ModelTurn turn = adapter.callModel(request(input), session, request -> {
            require("MASKED".equals(request.messages().get(0).text()), "Transport must receive effective input");
            return response();
        });
        AtomicInteger calls = new AtomicInteger();
        ToolResult result = adapter.callTool(turn.response().message().toolCalls().get(0), turn, session, call -> {
            calls.incrementAndGet();
            return ToolResult.of(call.callId(), call.toolName(), "PRIVATE");
        });
        AgentOutput output = adapter.afterAgent(AgentOutput.of(result.content()), session);
        require("MASKED".equals(output.text()), "Tool output modification must be applied");
        require(calls.get() == 1, "Expected one tool execution");
        require(stages.equals(List.of(GuardStage.AGENT_INPUT, GuardStage.MODEL_INPUT,
                GuardStage.MODEL_OUTPUT, GuardStage.TOOL_INPUT, GuardStage.TOOL_OUTPUT,
                GuardStage.AGENT_OUTPUT)), "Unexpected lifecycle order");
        verifyNoToolExecution(true, false);
        verifyNoToolExecution(false, true);
        System.out.println("PASS: six stages, effective modifications, tool blocking and per-turn tool filtering");
    }

    private static void verifyNoToolExecution(boolean block, boolean filter) {
        FrameworkGuardAdapter adapter = new FrameworkGuardAdapter(engine(new ArrayList<>(), block, filter));
        FrameworkGuardAdapter.RequestSession session = session();
        FrameworkGuardAdapter.ModelTurn turn = adapter.callModel(request(AgentInput.of("hello")), session,
                request -> response());
        AtomicInteger calls = new AtomicInteger();
        try {
            adapter.callTool(turn.response().message().toolCalls().get(0), turn, session, call -> {
                calls.incrementAndGet();
                return ToolResult.of(call.callId(), call.toolName(), "result");
            });
            throw new IllegalStateException("Expected tool blocking");
        } catch (GuardBlockedException expected) {
            require(expected.stage() == GuardStage.TOOL_INPUT, "Expected TOOL_INPUT");
            require(calls.get() == 0, "Blocked tool must not execute");
        }
    }

    private static FrameworkGuardAdapter.RequestSession session() {
        return FrameworkGuardAdapter.beginRequest(() -> GuardContext.builder()
                .agentId("demo-agent").tenantId("tenant-A").userId("service:demo").build());
    }

    private static ModelRequest request(AgentInput input) {
        return ModelRequest.builder().messages(List.of(input.message()))
                .tools(List.of(ToolDefinition.of("lookup", "demo", "{\"type\":\"object\"}")))
                .parameters(Map.of()).build();
    }

    private static ModelResponse response() {
        return ModelResponse.of(GuardMessage.builder().role(Role.ASSISTANT)
                .toolCalls(List.of(ToolCall.of("call-1", "lookup", "{}"))).build());
    }

    private static GuardEngine engine(List<GuardStage> stages, boolean blockTool, boolean filterTools) {
        return new GuardEngine() {
            @Override
            public <T> GuardOutcome<T> check(GuardStage stage, T payload, GuardContext context) {
                stages.add(stage);
                Object effective = payload;
                if (stage == GuardStage.AGENT_INPUT) {
                    effective = ((AgentInput) payload).withText("MASKED");
                }
                if (stage == GuardStage.TOOL_OUTPUT) {
                    effective = ((ToolResult) payload).withContent("MASKED");
                }
                if (stage == GuardStage.MODEL_INPUT && filterTools) {
                    effective = ((ModelRequest) payload).withTools(List.of());
                }
                GuardAction action = blockTool && stage == GuardStage.TOOL_INPUT
                        ? GuardAction.BLOCK : effective == payload ? GuardAction.PASS : GuardAction.MODIFY;
                @SuppressWarnings("unchecked")
                T value = (T) effective;
                return new GuardOutcome<>(stage, action, value, "demo", List.of(), List.of());
            }
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
