package org.agentguard.agt;

import io.agt.core.Identifiers;
import io.agt.core.InterventionPoint;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.agentguard.GuardStage;
import org.agentguard.core.GuardEngineBuilder;
import org.agentguard.engine.GuardEngine;

/**
 * 面向业务配置的 AGT 护栏入口，封装六阶段、规则标识与引擎创建。
 *
 * <p>仅复用现有策略评估，不读取 YAML、不扫描 SPI，也不启用预算或审批运行时。
 * 构建器限单线程配置，构建后的策略、Provider 和引擎可按其契约复用。
 */
public final class AgtGuards {
    private static final Set<InterventionPoint> POINTS =
            Collections.unmodifiableSet(
                    EnumSet.of(
                            InterventionPoint.INPUT,
                            InterventionPoint.PRE_MODEL_CALL,
                            InterventionPoint.POST_MODEL_CALL,
                            InterventionPoint.PRE_TOOL_CALL,
                            InterventionPoint.POST_TOOL_CALL,
                            InterventionPoint.OUTPUT));

    private AgtGuards() {}

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 按声明顺序组装内容与工具规则，构建时产生独立的不可变策略快照。
     * 不允许空配置，避免将未注册规则误认为护栏已经生效。
     */
    public static final class Builder {
        private String version = "code-v1";
        private int nextRuleId = 1;
        private final Map<String, ContentRule> rules = new LinkedHashMap<>();
        private final Map<String, ToolRule> tools = new LinkedHashMap<>();

        private Builder() {}

        public Builder version(String value) {
            version = Identifiers.require(value);
            return this;
        }

        /** 六阶段拒绝包含任意指定字面字符串的内容，不提供语义注入检测。 */
        public Builder denyText(String... values) {
            return addDeny(POINTS, values);
        }

        public Builder denyTextAt(GuardStage stage, String... values) {
            return addDeny(Collections.singleton(point(stage)), values);
        }

        public Builder maskInput(String source, String replacement) {
            return maskAt(GuardStage.AGENT_INPUT, source, replacement);
        }

        public Builder maskToolOutput(String source, String replacement) {
            return maskAt(GuardStage.TOOL_OUTPUT, source, replacement);
        }

        /** 同阶段同源字符串禁止重复替换；不同来源的替换按声明顺序串联。 */
        public Builder maskAt(GuardStage stage, String source, String replacement) {
            requireText(source);
            Objects.requireNonNull(replacement, "replacement");
            if (source.equals(replacement)) {
                throw new IllegalArgumentException("Replacement must change the source text");
            }
            return contentRule(
                    new ContentRule(
                            newRuleId(),
                            Collections.singleton(point(stage)),
                            Collections.emptyList(),
                            Collections.singletonMap(source, replacement)));
        }

        public Builder tool(String name, Consumer<ToolBuilder> configure) {
            requireNewTool(name);
            Objects.requireNonNull(configure, "configure");
            ToolBuilder builder = new ToolBuilder();
            configure.accept(builder);
            return toolRule(name, builder.build());
        }

        /** 接受现有工具规则，便于复用高级参数约束和审批需求配置。 */
        public Builder toolRule(String name, ToolRule rule) {
            requireNewTool(name);
            tools.put(name, Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /** 接受底层内容规则，同时检查标识重复及重叠阶段的替换冲突。 */
        public Builder contentRule(ContentRule rule) {
            Objects.requireNonNull(rule, "rule");
            if (rules.containsKey(rule.id())) {
                throw new IllegalArgumentException("Duplicate content rule ID: " + rule.id());
            }
            if (!POINTS.containsAll(rule.points())) {
                throw new IllegalArgumentException(
                        "Content rule must bind to the six supported stages");
            }
            for (ContentRule existing : rules.values()) {
                if (!Collections.disjoint(existing.points(), rule.points())
                        && !Collections.disjoint(
                                existing.replacements().keySet(), rule.replacements().keySet())) {
                    throw new IllegalArgumentException(
                            "Duplicate or conflicting replacement in overlapping stages");
                }
            }
            rules.put(rule.id(), rule);
            return this;
        }

        public StructuredPolicy buildPolicy() {
            if (rules.isEmpty() && tools.isEmpty()) {
                throw new IllegalStateException("Configure at least one content or tool rule");
            }
            StructuredPolicy.Builder policy = StructuredPolicy.builder(version).points(POINTS);
            for (Map.Entry<String, ToolRule> tool : tools.entrySet()) {
                policy.tool(tool.getKey(), tool.getValue());
            }
            for (ContentRule rule : rules.values()) {
                policy.content(rule);
            }
            return policy.build();
        }

        public AgtGuardProvider buildProvider() {
            return new AgtGuardProvider(buildPolicy());
        }

        public GuardEngine build() {
            return new GuardEngineBuilder().addProvider(buildProvider()).build();
        }

        private Builder addDeny(Set<InterventionPoint> points, String... values) {
            Objects.requireNonNull(values, "values");
            if (values.length == 0) {
                throw new IllegalArgumentException("Configure at least one denied text");
            }
            List<String> denied = new ArrayList<>();
            for (String value : values) {
                denied.add(requireText(value));
            }
            if (new LinkedHashSet<>(denied).size() != denied.size()) {
                throw new IllegalArgumentException("Duplicate denied text");
            }
            return contentRule(
                    new ContentRule(newRuleId(), points, denied, Collections.emptyMap()));
        }

        private void requireNewTool(String name) {
            Identifiers.require(name);
            if (tools.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate tool: " + name);
            }
        }

        private String newRuleId() {
            String id;
            do {
                id = "agt.fluent.rule." + nextRuleId++;
            } while (rules.containsKey(id));
            return id;
        }
    }

    /**
     * 单工具的权限及参数约束构建器。
     * 租户绑定自动声明必填字符串，重复参数必须明确消除而不是覆盖。
     */
    public static final class ToolBuilder {
        private final Set<String> permissions = new LinkedHashSet<>();
        private final Map<String, ParameterRule> parameters = new LinkedHashMap<>();
        private final Set<String> required = new LinkedHashSet<>();

        private ToolBuilder() {}

        public ToolBuilder requirePermission(String... values) {
            Objects.requireNonNull(values, "permissions");
            if (values.length == 0) {
                throw new IllegalArgumentException("Configure at least one permission");
            }
            for (String value : values) {
                if (!permissions.add(Identifiers.require(value))) {
                    throw new IllegalArgumentException("Duplicate permission: " + value);
                }
            }
            return this;
        }

        public ToolBuilder bindTenant(String parameter) {
            return parameter(parameter, ParameterRule.string("tenantId"), true);
        }

        public ToolBuilder requireString(String name) {
            return parameter(name, ParameterRule.string(null), true);
        }

        public ToolBuilder optionalString(String name) {
            return parameter(name, ParameterRule.string(null), false);
        }

        public ToolBuilder parameter(String name, ParameterRule rule, boolean mandatory) {
            Identifiers.require(name);
            Objects.requireNonNull(rule, "rule");
            if (parameters.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate parameter: " + name);
            }
            parameters.put(name, rule);
            if (mandatory) {
                required.add(name);
            }
            return this;
        }

        private ToolRule build() {
            ParameterRule arguments =
                    ParameterRule.object(parameters, required.toArray(new String[0]));
            return new ToolRule(permissions, arguments, false);
        }
    }

    private static String requireText(String value) {
        Objects.requireNonNull(value, "text");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
        return value;
    }

    private static InterventionPoint point(GuardStage stage) {
        return AgtGuardProvider.point(Objects.requireNonNull(stage, "stage"));
    }
}
