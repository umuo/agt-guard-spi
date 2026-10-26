package org.agentguard.model;

/** Token 用量，字段可能为 null（框架未提供时）。 */
public final class TokenUsage {

    private final Integer inputTokens;
    private final Integer outputTokens;

    private TokenUsage(Integer inputTokens, Integer outputTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    public static TokenUsage of(Integer inputTokens, Integer outputTokens) {
        return new TokenUsage(inputTokens, outputTokens);
    }

    public Integer inputTokens() {
        return inputTokens;
    }

    public Integer outputTokens() {
        return outputTokens;
    }

    public Integer totalTokens() {
        if (inputTokens == null && outputTokens == null) {
            return null;
        }
        return (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
    }

    @Override
    public String toString() {
        return "TokenUsage{input=" + inputTokens + ", output=" + outputTokens + '}';
    }
}
