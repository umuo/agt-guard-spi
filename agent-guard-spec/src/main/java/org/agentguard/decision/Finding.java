package org.agentguard.decision;

import org.agentguard.util.Immutables;

import java.util.Map;

/**
 * 一条风险命中记录。
 *
 * <p><b>安全约定</b>：{@link #message()} 可以对外（给终端用户或回传给 LLM）；
 * {@link #detail()} 与 {@link #evidence()} 仅用于内部日志与审计，<b>禁止</b>出现在任何对外内容中，
 * 否则攻击者可据此反推检测规则。{@link #toString()} 也不会输出这两个字段。</p>
 */
public final class Finding {

    private final String code;
    private final RiskCategory category;
    private final RiskLevel level;
    private final Double confidence;
    private final String message;
    private final String detail;
    private final Map<String, Object> evidence;

    private Finding(Builder b) {
        this.code = Immutables.requireNonNull(b.code, "code");
        this.category = b.category == null ? RiskCategory.OTHER : b.category;
        this.level = b.level == null ? RiskLevel.MEDIUM : b.level;
        if (b.confidence != null && (b.confidence < 0 || b.confidence > 1)) {
            throw new IllegalArgumentException("confidence must be in [0, 1]");
        }
        this.confidence = b.confidence;
        this.message = b.message;
        this.detail = b.detail;
        this.evidence = Immutables.map(b.evidence);
    }

    public static Finding of(String code, RiskCategory category, RiskLevel level) {
        return builder().code(code).category(category).level(level).build();
    }

    /** 机器可读的命中编码，如 PROMPT_INJECTION_DIRECT、TOOL_TENANT_MISMATCH。 */
    public String code() {
        return code;
    }

    public RiskCategory category() {
        return category;
    }

    public RiskLevel level() {
        return level;
    }

    /** 置信度 [0, 1]，未知时为 null。 */
    public Double confidence() {
        return confidence;
    }

    /** 可对外展示的安全文案，可能为 null。 */
    public String message() {
        return message;
    }

    /** 内部详情，禁止外泄。 */
    public String detail() {
        return detail;
    }

    /** 命中证据（命中片段、规则 id、模型分数等），禁止外泄。 */
    public Map<String, Object> evidence() {
        return evidence;
    }

    public Builder toBuilder() {
        return new Builder().code(code).category(category).level(level).confidence(confidence)
                .message(message).detail(detail).evidence(evidence);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "Finding{code=" + code + ", category=" + category + ", level=" + level
                + (confidence == null ? "" : ", confidence=" + confidence) + '}';
    }

    public static final class Builder {
        private String code;
        private RiskCategory category;
        private RiskLevel level;
        private Double confidence;
        private String message;
        private String detail;
        private Map<String, Object> evidence;

        private Builder() {
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder category(RiskCategory category) {
            this.category = category;
            return this;
        }

        public Builder level(RiskLevel level) {
            this.level = level;
            return this;
        }

        public Builder confidence(Double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder evidence(Map<String, Object> evidence) {
            this.evidence = evidence;
            return this;
        }

        public Finding build() {
            return new Finding(this);
        }
    }
}
