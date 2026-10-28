package org.agentguard.diagnostics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.agentguard.GuardStage;
import org.agentguard.engine.GuardEngine;
import org.agentguard.streaming.StreamingGuardEngine;
import org.agentguard.util.Immutables;

/**
 * 引擎配置的不可变快照，只保存规则标识和来源类别，不持有策略或请求载荷。
 * 注册不表示实际执行，流式接口存在也不表示所有模式均通过校验。
 */
public final class GuardDiagnostics {
    /**
     * 策略的配置来源类别，不包含真实文件路径或配置内容。
     */
    public enum PolicySource {
        UNKNOWN,
        PROGRAMMATIC,
        SYSTEM_PROPERTY,
        ENVIRONMENT
    }

    /**
     * Provider 的声明元信息，名称由 SDK 提供，默认摘要不打印名称。
     */
    public static final class Provider {
        private final String name;
        private final String type;
        private final PolicySource policySource;
        private final boolean streaming;

        public Provider(String name, String type, PolicySource policySource, boolean streaming) {
            this.name = Immutables.requireNonNull(name, "name");
            this.type = Immutables.requireNonNull(type, "type");
            this.policySource = Immutables.requireNonNull(policySource, "policySource");
            this.streaming = streaming;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public PolicySource policySource() {
            return policySource;
        }

        public boolean streaming() {
            return streaming;
        }

        @Override
        public String toString() {
            return "Provider{policySource=" + policySource + ", streaming=" + streaming + "}";
        }
    }

    /**
     * 单个已注册 Guard 的阶段与排序信息，不包含检查内容或请求结果。
     */
    public static final class Rule {
        private final String id;
        private final GuardStage stage;
        private final int order;

        public Rule(String id, GuardStage stage, int order) {
            this.id = Immutables.requireNonNull(id, "id");
            this.stage = Immutables.requireNonNull(stage, "stage");
            this.order = order;
        }

        public String id() {
            return id;
        }

        public GuardStage stage() {
            return stage;
        }

        public int order() {
            return order;
        }

        @Override
        public String toString() {
            return "Rule{stage=" + stage + ", order=" + order + "}";
        }
    }

    private final String engineType;
    private final boolean detailsAvailable;
    private final boolean dynamicPolicy;
    private final boolean streaming;
    private final List<Provider> providers;
    private final Map<GuardStage, List<Rule>> rulesByStage;

    public GuardDiagnostics(String engineType, boolean detailsAvailable, boolean dynamicPolicy,
            boolean streaming, List<Provider> providers, List<Rule> rules) {
        this.engineType = Immutables.requireNonNull(engineType, "engineType");
        this.detailsAvailable = detailsAvailable;
        this.dynamicPolicy = dynamicPolicy;
        this.streaming = streaming;
        this.providers = Immutables.list(providers);
        Map<GuardStage, List<Rule>> indexed = new EnumMap<GuardStage, List<Rule>>(GuardStage.class);
        for (GuardStage stage : GuardStage.values()) {
            indexed.put(stage, new ArrayList<Rule>());
        }
        for (Rule rule : Immutables.list(rules)) {
            indexed.get(rule.stage()).add(rule);
        }
        for (GuardStage stage : GuardStage.values()) {
            indexed.put(stage, Immutables.list(indexed.get(stage)));
        }
        this.rulesByStage = Immutables.map(indexed);
    }

    /**
     * 只读取可选快照，诊断提供者故障时返回未知详情，不影响实际安全执行。
     */
    public static GuardDiagnostics inspect(GuardEngine engine) {
        Immutables.requireNonNull(engine, "engine");
        if (engine instanceof DiagnosableGuardEngine) {
            try {
                GuardDiagnostics diagnostics = ((DiagnosableGuardEngine) engine).diagnostics();
                if (diagnostics != null) {
                    return diagnostics;
                }
            } catch (RuntimeException ignored) {
                // 元信息读取失败不代表没有 Guard，也不能替换原始安全异常。
            }
        }
        return new GuardDiagnostics(engine.getClass().getName(), false, false,
                engine instanceof StreamingGuardEngine, null, null);
    }

    public String engineType() {
        return engineType;
    }

    public boolean detailsAvailable() {
        return detailsAvailable;
    }

    /** 仅在 detailsAvailable 为 true 时有效。 */
    public boolean dynamicPolicy() {
        return dynamicPolicy;
    }

    public boolean streaming() {
        return streaming;
    }

    public List<Provider> providers() {
        return providers;
    }

    public Map<GuardStage, List<Rule>> rulesByStage() {
        return rulesByStage;
    }

    @Override
    public String toString() {
        int count = 0;
        for (List<Rule> rules : rulesByStage.values()) {
            count += rules.size();
        }
        return "GuardDiagnostics{detailsAvailable=" + detailsAvailable + ", providers=" + providers.size()
                + ", rules=" + count + ", dynamicPolicy=" + dynamicPolicy + ", streaming=" + streaming + "}";
    }
}
