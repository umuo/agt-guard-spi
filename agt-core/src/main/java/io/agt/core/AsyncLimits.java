package io.agt.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 有界异步工作池容量限制配置。
 *
 * <p>有限工作池；同步策略/审批和阻塞工具均不得占用 provider 的完成线程。
 */
public final class AsyncLimits {
    private final int workerThreads;
    private final int queueCapacity;

    @JsonCreator
    public AsyncLimits(
            @JsonProperty("workerThreads") int workerThreads,
            @JsonProperty("queueCapacity") int queueCapacity) {

        if (workerThreads < 1 || workerThreads > 64 || queueCapacity < 1 || queueCapacity > 4096) {
            throw new IllegalArgumentException("Invalid bounded async capacity");
        }

        this.workerThreads = workerThreads;
        this.queueCapacity = queueCapacity;
    }

    @JsonProperty("workerThreads")
    public int workerThreads() {
        return workerThreads;
    }

    @JsonProperty("queueCapacity")
    public int queueCapacity() {
        return queueCapacity;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        AsyncLimits other = (AsyncLimits) value;
        return Objects.equals(workerThreads, other.workerThreads)
                && Objects.equals(queueCapacity, other.queueCapacity);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(workerThreads);
        result = 31 * result + Objects.hashCode(queueCapacity);
        return result;
    }

    @Override
    public String toString() {
        return "AsyncLimits[workerThreads="
                + workerThreads
                + ", queueCapacity="
                + queueCapacity
                + "]";
    }

    public static AsyncLimits defaults() {
        return new AsyncLimits(4, 128);
    }
}
