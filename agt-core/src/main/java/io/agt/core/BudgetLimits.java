package io.agt.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 会话预算与生命周期上限配置。
 *
 * <p>限制单会话内允许的模型调用次数、工具调用次数及最长运行毫秒数。
 */
public final class BudgetLimits {
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final long maxDurationMillis;

    @JsonCreator
    public BudgetLimits(
            @JsonProperty("maxModelCalls") int maxModelCalls,
            @JsonProperty("maxToolCalls") int maxToolCalls,
            @JsonProperty("maxDurationMillis") long maxDurationMillis) {

        if (maxModelCalls < 1
                || maxToolCalls < 0
                || maxDurationMillis < 1
                || maxDurationMillis > 86_400_000) {
            throw new IllegalArgumentException("Invalid finite budget");
        }

        this.maxModelCalls = maxModelCalls;
        this.maxToolCalls = maxToolCalls;
        this.maxDurationMillis = maxDurationMillis;
    }

    @JsonProperty("maxModelCalls")
    public int maxModelCalls() {
        return maxModelCalls;
    }

    @JsonProperty("maxToolCalls")
    public int maxToolCalls() {
        return maxToolCalls;
    }

    @JsonProperty("maxDurationMillis")
    public long maxDurationMillis() {
        return maxDurationMillis;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        BudgetLimits other = (BudgetLimits) value;
        return Objects.equals(maxModelCalls, other.maxModelCalls)
                && Objects.equals(maxToolCalls, other.maxToolCalls)
                && Objects.equals(maxDurationMillis, other.maxDurationMillis);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(maxModelCalls);
        result = 31 * result + Objects.hashCode(maxToolCalls);
        result = 31 * result + Objects.hashCode(maxDurationMillis);
        return result;
    }

    @Override
    public String toString() {
        return "BudgetLimits[maxModelCalls="
                + maxModelCalls
                + ", maxToolCalls="
                + maxToolCalls
                + ", maxDurationMillis="
                + maxDurationMillis
                + "]";
    }

    public static BudgetLimits defaults() {
        return new BudgetLimits(20, 50, 300_000);
    }
}
