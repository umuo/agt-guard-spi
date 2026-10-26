package io.agt.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * 会话数据访问额度和单次返回数量限制配置。
 *
 * <p>不把数据访问记成模型或工具执行。maxCalls 计受控源接口调用，maxItems 限制单次消息或文档数量。
 */
public final class DataLimits {
    private final int maxCalls;
    private final int maxItems;

    @JsonCreator
    public DataLimits(
            @JsonProperty("maxCalls") int maxCalls, @JsonProperty("maxItems") int maxItems) {

        if (maxCalls < 1 || maxCalls > 1_000_000 || maxItems < 1 || maxItems > 10_000) {
            throw new IllegalArgumentException("Invalid data limits");
        }

        this.maxCalls = maxCalls;
        this.maxItems = maxItems;
    }

    @JsonProperty("maxCalls")
    public int maxCalls() {
        return maxCalls;
    }

    @JsonProperty("maxItems")
    public int maxItems() {
        return maxItems;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        DataLimits other = (DataLimits) value;
        return Objects.equals(maxCalls, other.maxCalls) && Objects.equals(maxItems, other.maxItems);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(maxCalls);
        result = 31 * result + Objects.hashCode(maxItems);
        return result;
    }

    @Override
    public String toString() {
        return "DataLimits[maxCalls=" + maxCalls + ", maxItems=" + maxItems + "]";
    }

    public static DataLimits defaults() {
        return new DataLimits(1000, 1000);
    }
}
