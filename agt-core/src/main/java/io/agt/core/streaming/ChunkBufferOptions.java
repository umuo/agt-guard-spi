package io.agt.core.streaming;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agt.core.Identifiers;
import io.agt.core.Java8;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 配置有界流式文本缓冲，使用显式规则标识选择本地内容策略。
 */
public final class ChunkBufferOptions {
    private final int chunkCharacters;
    private final String boundaries;
    private final Duration maxWait;
    private final int maxBufferedCharacters;
    private final Set<String> localRules;

    @JsonCreator
    public ChunkBufferOptions(
            @JsonProperty("chunkCharacters") int chunkCharacters,
            @JsonProperty("boundaries") String boundaries,
            @JsonProperty("maxWait") Duration maxWait,
            @JsonProperty("maxBufferedCharacters") int maxBufferedCharacters,
            @JsonProperty("localRules") Set<String> localRules) {

        Objects.requireNonNull(boundaries, "boundaries");
        Objects.requireNonNull(maxWait, "maxWait");
        localRules = Java8.copySet(localRules);
        localRules.forEach(Identifiers::require);
        if (chunkCharacters < 1
                || maxBufferedCharacters < 2
                || maxBufferedCharacters > 65536
                || chunkCharacters > maxBufferedCharacters
                || boundaries.length() > 256
                || maxWait.compareTo(Duration.ofMillis(1)) < 0
                || maxWait.compareTo(Duration.ofSeconds(60)) > 0
                || localRules.isEmpty()
                || localRules.size() > 256) {
            throw new IllegalArgumentException("invalid guarded streaming options");
        }

        this.chunkCharacters = chunkCharacters;
        this.boundaries = boundaries;
        this.maxWait = maxWait;
        this.maxBufferedCharacters = maxBufferedCharacters;
        this.localRules = localRules;
    }

    @JsonProperty("chunkCharacters")
    public int chunkCharacters() {
        return chunkCharacters;
    }

    @JsonProperty("boundaries")
    public String boundaries() {
        return boundaries;
    }

    @JsonProperty("maxWait")
    public Duration maxWait() {
        return maxWait;
    }

    @JsonProperty("maxBufferedCharacters")
    public int maxBufferedCharacters() {
        return maxBufferedCharacters;
    }

    @JsonProperty("localRules")
    public Set<String> localRules() {
        return localRules;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (value == null || getClass() != value.getClass()) {
            return false;
        }
        ChunkBufferOptions other = (ChunkBufferOptions) value;
        return Objects.equals(chunkCharacters, other.chunkCharacters)
                && Objects.equals(boundaries, other.boundaries)
                && Objects.equals(maxWait, other.maxWait)
                && Objects.equals(maxBufferedCharacters, other.maxBufferedCharacters)
                && Objects.equals(localRules, other.localRules);
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(chunkCharacters);
        result = 31 * result + Objects.hashCode(boundaries);
        result = 31 * result + Objects.hashCode(maxWait);
        result = 31 * result + Objects.hashCode(maxBufferedCharacters);
        result = 31 * result + Objects.hashCode(localRules);
        return result;
    }

    @Override
    public String toString() {
        return "ChunkBufferOptions[chunkCharacters="
                + chunkCharacters
                + ", boundaries="
                + boundaries
                + ", maxWait="
                + maxWait
                + ", maxBufferedCharacters="
                + maxBufferedCharacters
                + ", localRules="
                + localRules
                + "]";
    }

    public static ChunkBufferOptions defaults(Set<String> localRules) {
        return new ChunkBufferOptions(20, "，。！？\n", Duration.ofMillis(50), 4096, localRules);
    }
}
