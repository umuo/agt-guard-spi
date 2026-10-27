package org.agentguard.core;

import java.util.Objects;
import java.util.function.Consumer;
import org.agentguard.GuardStage;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.streaming.GuardStream;

/**
 * 有界完整响应缓冲，原文一致性确认和完整检查之后才释放有效文本。
 * 会话关闭、错误或上游完成之后不接受后续片段。
 */
final class StrictGuardStream implements GuardStream {
    private final int limit;
    private final Consumer<String> output;
    private final StringBuilder pending = new StringBuilder();
    private boolean sealed;
    private boolean closed;

    StrictGuardStream(int limit, Consumer<String> output) {
        this.limit = limit;
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public synchronized void accept(String text) {
        if (closed || sealed) {
            return;
        }
        Objects.requireNonNull(text, "text");
        if (text.length() > limit - pending.length()) {
            throw failed("Streaming buffer capacity exceeded");
        }
        pending.append(text);
    }

    @Override
    public synchronized void seal(String authoritativeText) {
        if (closed) {
            throw failed("Streaming buffer is closed");
        }
        String text = authoritativeText == null ? "" : authoritativeText;
        if (text.length() > limit || (pending.length() > 0 && !pending.toString().equals(text))) {
            throw failed("Streaming body differs from accumulated chunks or exceeds capacity");
        }
        sealed = true;
    }

    @Override
    public synchronized void complete(String checkedText) {
        if (closed || !sealed) {
            throw failed("Seal the streaming body before completing it");
        }
        String text = checkedText == null ? "" : checkedText;
        if (text.length() > limit) {
            throw failed("Checked output exceeds streaming capacity");
        }
        close();
        if (!text.isEmpty()) {
            output.accept(text);
        }
    }

    private GuardExecutionException failed(String message) {
        close();
        return new GuardExecutionException("stream.buffer", GuardStage.MODEL_OUTPUT, message,
                new IllegalStateException(message));
    }

    @Override
    public synchronized void close() {
        closed = true;
        pending.setLength(0);
    }
}
