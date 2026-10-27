package org.agentguard.streaming;

import java.util.function.Consumer;
import org.agentguard.context.GuardContext;
import org.agentguard.engine.GuardEngine;

/**
 * 可选流式引擎能力，框架通过规范接口发现支持，避免依赖具体 SDK。
 * 校验在服务构建时执行，流会话在每轮模型调用时创建并由框架关闭。
 */
public interface StreamingGuardEngine extends GuardEngine {
    void validateStreaming(GuardStreamOptions options);

    GuardStream openStream(GuardStreamOptions options, GuardContext context,
            Consumer<String> output, Consumer<Throwable> failure);
}
