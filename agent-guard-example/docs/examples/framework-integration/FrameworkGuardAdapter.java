package guide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.engine.GuardEngine;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.model.AgentInput;
import org.agentguard.model.AgentOutput;
import org.agentguard.model.ModelRequest;
import org.agentguard.model.ModelResponse;
import org.agentguard.model.Role;
import org.agentguard.model.ToolCall;
import org.agentguard.model.ToolDefinition;
import org.agentguard.model.ToolResult;
import org.agentguard.spi.GuardEngineFactory;

/**
 * 新 Agent 框架的护栏接入参考，生产依赖仅包含 spec。
 * 本示例使用 spec 对象表达内部执行边界，原生框架仍须实现无损转换器。
 */
public final class FrameworkGuardAdapter {
    private final GuardEngine engine;

    public FrameworkGuardAdapter(GuardEngine engine) {
        this.engine = engine;
    }

    /**
     * 在 Agent 构建阶段发现工厂；显式引擎优先，没有 SDK 时返回 null。
     * 发现全部工厂并检查唯一性之后，才创建引擎。
     */
    public static GuardEngine discover(GuardEngine explicit, boolean autoDiscovery, ClassLoader loader) {
        if (explicit != null || !autoDiscovery) {
            return explicit;
        }
        ClassLoader effectiveLoader = loader;
        if (effectiveLoader == null) {
            effectiveLoader = FrameworkGuardAdapter.class.getClassLoader();
        }
        try {
            List<GuardEngineFactory> factories = new ArrayList<>();
            for (GuardEngineFactory factory : ServiceLoader.load(GuardEngineFactory.class, effectiveLoader)) {
                factories.add(factory);
            }
            if (factories.isEmpty()) {
                return null;
            }
            if (factories.size() > 1) {
                throw new IllegalStateException("Multiple GuardEngineFactory implementations; configure an explicit engine");
            }
            return Objects.requireNonNull(factories.get(0).create(), "GuardEngineFactory returned null");
        } catch (RuntimeException | ServiceConfigurationError | LinkageError failure) {
            throw new IllegalStateException("Agent Guard initialization failed", failure);
        }
    }

    public static RequestSession beginRequest(Supplier<GuardContext> contextFactory) {
        return new RequestSession(Objects.requireNonNull(contextFactory.get(), "GuardContext"));
    }

    public AgentInput beforeAgent(AgentInput input, RequestSession session) {
        return checked(GuardStage.AGENT_INPUT, input, session, 0);
    }

    /**
     * 每一次实际模型尝试都通过此入口，返回本轮有效响应及有效工具集合。
     * transport 必须发送传入的有效请求，不得恢复原始消息或默认工具。
     */
    public ModelTurn callModel(ModelRequest request, RequestSession session,
            Function<ModelRequest, ModelResponse> transport) {
        int iteration = session.nextIteration.getAndIncrement();
        ModelRequest effective = checked(GuardStage.MODEL_INPUT, request, session, iteration);
        Set<String> allowed = new HashSet<>();
        for (ToolDefinition tool : effective.tools()) {
            allowed.add(tool.name());
        }
        ModelResponse raw = Objects.requireNonNull(transport.apply(effective), "model response");
        ModelResponse response = checked(GuardStage.MODEL_OUTPUT, raw, session, iteration);
        if (response.message().role() != Role.ASSISTANT) {
            throw adaptationFailure(GuardStage.MODEL_OUTPUT, "Model response must be an assistant message");
        }
        return new ModelTurn(effective, response, Set.copyOf(allowed), iteration);
    }

    /**
     * 每一次实际工具调用都通过此入口，必须绑定产生该调用的模型轮次。
     * executor 应完成参数转换和实际调度，且只能执行传入的有效参数。
     */
    public ToolResult callTool(ToolCall requested, ModelTurn turn, RequestSession session,
            Function<ToolCall, ToolResult> executor) {
        if (!turn.allowedTools().contains(requested.toolName())) {
            throw new GuardBlockedException(GuardStage.TOOL_INPUT,
                    "Tool is absent from this model turn's allowed set", List.of());
        }
        ToolCall effective = checked(GuardStage.TOOL_INPUT, requested, session, turn.iteration());
        requireSameCall(requested.callId(), requested.toolName(), effective.callId(), effective.toolName(),
                GuardStage.TOOL_INPUT);
        ToolResult raw = Objects.requireNonNull(executor.apply(effective), "tool result");
        requireSameCall(effective.callId(), effective.toolName(), raw.callId(), raw.toolName(),
                GuardStage.TOOL_OUTPUT);
        ToolResult result = checked(GuardStage.TOOL_OUTPUT, raw, session, turn.iteration());
        requireSameCall(raw.callId(), raw.toolName(), result.callId(), result.toolName(), GuardStage.TOOL_OUTPUT);
        if (raw.isError() != result.isError()) {
            throw adaptationFailure(GuardStage.TOOL_OUTPUT, "Tool error flag cannot be rewritten");
        }
        return result;
    }

    public AgentOutput afterAgent(AgentOutput output, RequestSession session) {
        return checked(GuardStage.AGENT_OUTPUT, output, session, 0);
    }

    private <T> T checked(GuardStage stage, T payload, RequestSession session, int iteration) {
        Objects.requireNonNull(payload, "payload");
        if (engine == null) {
            return payload;
        }
        GuardContext context = session.root.withStage(stage).withIteration(iteration);
        // 本参考将所有 REJECT 也视为终止；不实现 reply 替代执行的语义。
        return engine.check(stage, payload, context).payloadOrThrow();
    }

    private static void requireSameCall(String beforeId, String beforeName, String afterId,
            String afterName, GuardStage stage) {
        if (!Objects.equals(beforeId, afterId) || !Objects.equals(beforeName, afterName)) {
            throw adaptationFailure(stage, "Tool name and call ID cannot be rewritten");
        }
    }

    private static GuardExecutionException adaptationFailure(GuardStage stage, String message) {
        return new GuardExecutionException("framework.adapter", stage, message,
                new IllegalArgumentException(message));
    }

    /**
     * 一次请求的隔离状态；上下文工厂应返回新建的根上下文。
     * 显式传递到异步任务，不依赖线程局部变量传播。
     */
    public static final class RequestSession {
        private final GuardContext root;
        private final AtomicInteger nextIteration = new AtomicInteger();

        private RequestSession(GuardContext root) {
            this.root = root;
        }
    }

    /**
     * 一次模型调用的有效结果及工具授权范围。
     * 工具任务应持有其来源轮次，避免并发时读取错误的最新轮次。
     */
    public record ModelTurn(ModelRequest request, ModelResponse response, Set<String> allowedTools, int iteration) {
        public ModelTurn {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(response, "response");
            allowedTools = Set.copyOf(allowedTools);
        }
    }
}
