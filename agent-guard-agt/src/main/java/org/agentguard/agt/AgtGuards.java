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
            version = identifier(value, "version");
            return this;
        }

        /**
         * 在独立草稿上应用可复用配置，回调正常结束后才提交配置快照。
         * 回调只能配置收到的草稿，外部系统副作用不在回滚范围内。
         */
        public Builder configure(Consumer<Builder> configuration) {
            Objects.requireNonNull(configuration, "configuration");
            Builder draft = new Builder();
            draft.copyFrom(this);
            configuration.accept(draft);
            copyFrom(draft);
            return this;
        }

        private void copyFrom(Builder source) {
            version = source.version;
            nextRuleId = source.nextRuleId;
            rules.clear();
            rules.putAll(source.rules);
            tools.clear();
            tools.putAll(source.tools);
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

        /**
         * 按 Map 的迭代顺序批量脱敏，整个批次验证失败时不添加任何规则。
         */
        public Builder maskInput(Map<String, String> replacements) {
            return maskAt(GuardStage.AGENT_INPUT, replacements);
        }

        public Builder maskToolOutput(Map<String, String> replacements) {
            return maskAt(GuardStage.TOOL_OUTPUT, replacements);
        }

        public Builder maskAt(GuardStage stage, Map<String, String> replacements) {
            point(stage);
            Objects.requireNonNull(replacements, "replacements");
            if (replacements.isEmpty()) {
                throw invalid(
                        "contentRules." + stage + ".replacements",
                        "Configure at least one replacement");
            }
            Map<String, String> snapshot = new LinkedHashMap<>(replacements);
            return configure(
                    draft ->
                            snapshot.forEach(
                                    (source, target) -> draft.maskAt(stage, source, target)));
        }

        /** 同阶段同源字符串禁止重复替换；不同来源的替换按声明顺序串联。 */
        public Builder maskAt(GuardStage stage, String source, String replacement) {
            point(stage);
            String location = "contentRules." + stage + ".replacements";
            requireText(source, location + ".source");
            Objects.requireNonNull(replacement, location + ".target must not be null");
            if (source.equals(replacement)) {
                throw invalid(location, "Replacement must change the source text");
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
            Objects.requireNonNull(configure, "tools." + name + ".configure must not be null");
            ToolBuilder builder = new ToolBuilder(name);
            configure.accept(builder);
            return toolRule(name, builder.build());
        }

        /**
         * 同时声明工具的必需权限与可信租户绑定，其余参数仍使用现有 ToolBuilder。
         * 不从工具名称推断权限，也不代表工具实现本身没有写入副作用。
         */
        public Builder tenantTool(
                String name,
                String permission,
                String tenantParameter,
                Consumer<ToolBuilder> configure) {
            Objects.requireNonNull(configure, "configure");
            return tool(
                    name,
                    tool -> {
                        tool.requirePermission(permission).bindTenant(tenantParameter);
                        configure.accept(tool);
                    });
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
                throw invalid("contentRules." + rule.id(), "Duplicate content rule ID");
            }
            if (!POINTS.containsAll(rule.points())) {
                throw invalid(
                        "contentRules." + rule.id() + ".points",
                        "Content rule must bind to the six supported stages");
            }
            for (ContentRule existing : rules.values()) {
                if (!Collections.disjoint(existing.points(), rule.points())
                        && !Collections.disjoint(
                                existing.replacements().keySet(), rule.replacements().keySet())) {
                    throw invalid(
                            "contentRules." + rule.id() + ".replacements",
                            "Duplicate or conflicting replacement in overlapping stages; remove the repeated source");
                }
            }
            rules.put(rule.id(), rule);
            return this;
        }

        public StructuredPolicy buildPolicy() {
            if (rules.isEmpty() && tools.isEmpty()) {
                throw new IllegalStateException(
                        "policy: Configure at least one content or tool rule before build");
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
                throw invalid("contentRules.denyText", "Configure at least one denied text");
            }
            List<String> denied = new ArrayList<>();
            for (String value : values) {
                denied.add(requireText(value, "contentRules.denyText"));
            }
            if (new LinkedHashSet<>(denied).size() != denied.size()) {
                throw invalid(
                        "contentRules.denyText", "Duplicate denied text; remove repeated entries");
            }
            return contentRule(
                    new ContentRule(newRuleId(), points, denied, Collections.emptyMap()));
        }

        private void requireNewTool(String name) {
            identifier(name, "tools.name");
            if (tools.containsKey(name)) {
                throw invalid(
                        "tools." + name,
                        "Duplicate tool; combine its configuration into one declaration");
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

        private final String toolName;

        private ToolBuilder(String toolName) {
            this.toolName = toolName;
        }

        private String location(String field) {
            return "tools." + toolName + "." + field;
        }

        public ToolBuilder requirePermission(String... values) {
            Objects.requireNonNull(values, location("permissions"));
            if (values.length == 0) {
                throw invalid(location("permissions"), "Configure at least one permission");
            }
            Set<String> combined = new LinkedHashSet<>(permissions);
            for (String value : values) {
                if (!combined.add(identifier(value, location("permissions")))) {
                    throw invalid(
                            location("permissions"),
                            "Duplicate permission; remove repeated entries");
                }
            }
            permissions.clear();
            permissions.addAll(combined);
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

        /**
         * 批量声明必填字符串，所有名称验证成功后才写入当前工具。
         */
        public ToolBuilder requireStrings(String... names) {
            return strings(true, names);
        }

        public ToolBuilder optionalStrings(String... names) {
            return strings(false, names);
        }

        private ToolBuilder strings(boolean mandatory, String... names) {
            Objects.requireNonNull(names, location("parameters"));
            if (names.length == 0) {
                throw invalid(location("parameters"), "Configure at least one string parameter");
            }
            Map<String, ParameterRule> added = new LinkedHashMap<>();
            for (String name : names) {
                identifier(name, location("parameters.name"));
                if (parameters.containsKey(name) || added.containsKey(name)) {
                    throw duplicateParameter(name);
                }
                added.put(name, ParameterRule.string(null));
            }
            parameters.putAll(added);
            if (mandatory) {
                required.addAll(added.keySet());
            }
            return this;
        }

        public ToolBuilder parameter(String name, ParameterRule rule, boolean mandatory) {
            identifier(name, location("parameters.name"));
            Objects.requireNonNull(rule, location("parameters." + name) + " must not be null");
            if (parameters.containsKey(name)) {
                throw duplicateParameter(name);
            }
            parameters.put(name, rule);
            if (mandatory) {
                required.add(name);
            }
            return this;
        }

        private IllegalArgumentException duplicateParameter(String name) {
            ParameterRule existing = parameters.get(name);
            String reason =
                    existing != null && "tenantId".equals(existing.equalsContext())
                            ? "Duplicate parameter; bindTenant already declares its parameter"
                            : "Duplicate parameter; declare each parameter only once";
            return invalid(location("parameters." + name), reason);
        }

        private ToolRule build() {
            ParameterRule arguments =
                    ParameterRule.object(parameters, required.toArray(new String[0]));
            return new ToolRule(permissions, arguments, false);
        }
    }

    private static String requireText(String value, String location) {
        Objects.requireNonNull(value, location + " must not be null");
        if (value.isEmpty()) {
            throw invalid(location, "Text must not be empty");
        }
        return value;
    }

    private static String identifier(String value, String location) {
        try {
            return Identifiers.require(value);
        } catch (IllegalArgumentException error) {
            throw invalid(location, "Expected 1..128 characters from [a-zA-Z0-9_.:@/-]");
        }
    }

    private static IllegalArgumentException invalid(String location, String reason) {
        return new IllegalArgumentException(location + ": " + reason);
    }

    private static InterventionPoint point(GuardStage stage) {
        return AgtGuardProvider.point(Objects.requireNonNull(stage, "stage"));
    }
}
