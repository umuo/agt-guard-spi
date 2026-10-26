package io.agt.core.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.Java8;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 经过模板恢复的不可变执行计划。
 *
 * <p>程序与目录由宿主绑定，参数仍须通过工具策略和固定模板；默认日志不显示原文。
 */
public final class CommandExecution {
    private final Path executable;
    private final Path directory;
    private final List<String> arguments;
    private final CommandLimits limits;

    @JsonCreator
    public CommandExecution(
            @JsonProperty("executable") Path executable,
            @JsonProperty("directory") Path directory,
            @JsonProperty("arguments") List<String> arguments,
            @JsonProperty("limits") CommandLimits limits) {

        Objects.requireNonNull(executable);
        Objects.requireNonNull(directory);
        Objects.requireNonNull(limits);
        arguments = Java8.copyList(arguments);

        this.executable = executable;
        this.directory = directory;
        this.arguments = arguments;
        this.limits = limits;
    }

    @JsonProperty("executable")
    public Path executable() {
        return executable;
    }

    @JsonProperty("directory")
    public Path directory() {
        return directory;
    }

    @JsonProperty("arguments")
    public List<String> arguments() {
        return arguments;
    }

    @JsonProperty("limits")
    public CommandLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        CommandExecution other = (CommandExecution) value;
        return Objects.equals(executable, other.executable)
                && Objects.equals(directory, other.directory)
                && Objects.equals(arguments, other.arguments)
                && Objects.equals(limits, other.limits);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(executable);
        result = 31 * result + Objects.hashCode(directory);
        result = 31 * result + Objects.hashCode(arguments);
        result = 31 * result + Objects.hashCode(limits);
        return result;
    }

    @Override
    public String toString() {
        return "CommandExecution[argumentCount=" + arguments.size() + "]";
    }
}
