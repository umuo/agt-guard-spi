package io.agt.core.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 固定命令执行的有限资源配置。
 *
 * <p>输出按合并 stdout/stderr 的字节计数；并发容量属于单个命令实例。
 */
public final class CommandLimits {
    private final long timeoutMillis;
    private final int maxOutputBytes;
    private final int maxConcurrent;

    @JsonCreator
    public CommandLimits(
            @JsonProperty("timeoutMillis") long timeoutMillis,
            @JsonProperty("maxOutputBytes") int maxOutputBytes,
            @JsonProperty("maxConcurrent") int maxConcurrent) {

        if (timeoutMillis < 1
                || timeoutMillis > 30000
                || maxOutputBytes < 1
                || maxOutputBytes > 1048576
                || maxConcurrent < 1
                || maxConcurrent > 32) {
            throw new IllegalArgumentException("Invalid command limits");
        }

        this.timeoutMillis = timeoutMillis;
        this.maxOutputBytes = maxOutputBytes;
        this.maxConcurrent = maxConcurrent;
    }

    @JsonProperty("timeoutMillis")
    public long timeoutMillis() {
        return timeoutMillis;
    }

    @JsonProperty("maxOutputBytes")
    public int maxOutputBytes() {
        return maxOutputBytes;
    }

    @JsonProperty("maxConcurrent")
    public int maxConcurrent() {
        return maxConcurrent;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        CommandLimits other = (CommandLimits) value;
        return Objects.equals(timeoutMillis, other.timeoutMillis)
                && Objects.equals(maxOutputBytes, other.maxOutputBytes)
                && Objects.equals(maxConcurrent, other.maxConcurrent);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(timeoutMillis);
        result = 31 * result + Objects.hashCode(maxOutputBytes);
        result = 31 * result + Objects.hashCode(maxConcurrent);
        return result;
    }

    @Override
    public String toString() {
        return "CommandLimits[timeoutMillis="
                + timeoutMillis
                + ", maxOutputBytes="
                + maxOutputBytes
                + ", maxConcurrent="
                + maxConcurrent
                + "]";
    }

    public static final CommandLimits DEFAULT = new CommandLimits(5000, 65536, 2);
}
