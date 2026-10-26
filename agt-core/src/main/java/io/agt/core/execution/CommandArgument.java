package io.agt.core.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.Identifiers;
import io.agt.core.Java8;
import java.util.List;
import java.util.Objects;

/**
 * 宿主固定的单个 argv 参数。
 *
 * <p>模型只可选择显式枚举值，不能提供命令、脚本、任意路径或自由文本。
 */
public final class CommandArgument {
    private final String literal;
    private final String parameter;
    private final List<String> choices;

    @JsonCreator
    public CommandArgument(
            @JsonProperty("literal") String literal,
            @JsonProperty("parameter") String parameter,
            @JsonProperty("choices") List<String> choices) {

        choices = choices == null ? Java8.list() : Java8.copyList(choices);
        if ((literal == null) == (parameter == null)) {
            throw new IllegalArgumentException("Choose literal or parameter");
        }
        if (literal != null) {
            check(literal);
            if (!choices.isEmpty()) {
                throw new IllegalArgumentException("Literal cannot have choices");
            }
        } else {
            Identifiers.require(parameter);
            if (choices.isEmpty() || choices.size() > 100) {
                throw new IllegalArgumentException("Bounded choices required");
            }
            choices.forEach(CommandArgument::check);
        }

        this.literal = literal;
        this.parameter = parameter;
        this.choices = choices;
    }

    @JsonProperty("literal")
    public String literal() {
        return literal;
    }

    @JsonProperty("parameter")
    public String parameter() {
        return parameter;
    }

    @JsonProperty("choices")
    public List<String> choices() {
        return choices;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        CommandArgument other = (CommandArgument) value;
        return Objects.equals(literal, other.literal)
                && Objects.equals(parameter, other.parameter)
                && Objects.equals(choices, other.choices);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(literal);
        result = 31 * result + Objects.hashCode(parameter);
        result = 31 * result + Objects.hashCode(choices);
        return result;
    }

    @Override
    public String toString() {
        return "CommandArgument[literal="
                + literal
                + ", parameter="
                + parameter
                + ", choices="
                + choices
                + "]";
    }

    public static CommandArgument literal(String value) {
        return new CommandArgument(value, null, Java8.list());
    }

    public static CommandArgument choice(String name, String... values) {
        return new CommandArgument(null, name, Java8.list(values));
    }

    private static void check(String value) {
        if (value == null || value.length() > 4096 || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid command argument");
        }
    }
}
