package io.agt.core.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agt.core.BudgetLimits;
import io.agt.core.GovernanceException;
import io.agt.core.Identifiers;
import io.agt.core.InterventionPoint;
import io.agt.core.Java8;
import io.agt.core.PolicyDecision;
import io.agt.core.PolicyEvaluator;
import io.agt.core.PolicyRequest;
import io.agt.core.data.DataLimits;
import io.agt.core.data.DataResource;
import io.agt.core.data.DataRule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 基于结构化文档的确定性策略评估器实现。
 *
 * <p>不执行表达式、不访问网络、策略实例启动后不可变。
 */
public final class StructuredPolicy implements PolicyEvaluator {
    /**
     * 结构化策略文档，保存版本、检查点与治理规则。
     */
    public static final class Document {
        private final String apiVersion;
        private final String version;
        private final Set<InterventionPoint> interventionPoints;
        private final BudgetLimits budget;
        private final Map<String, ToolRule> tools;
        private final List<ContentRule> contentRules;
        private final Map<String, DataRule> resources;
        private final DataLimits dataLimits;

        @JsonCreator
        public Document(
                @JsonProperty("apiVersion") String apiVersion,
                @JsonProperty("version") String version,
                @JsonProperty("interventionPoints") Set<InterventionPoint> interventionPoints,
                @JsonProperty("budget") BudgetLimits budget,
                @JsonProperty("tools") Map<String, ToolRule> tools,
                @JsonProperty("contentRules") List<ContentRule> contentRules,
                @JsonProperty("resources") Map<String, DataRule> resources,
                @JsonProperty("dataLimits") DataLimits dataLimits) {

            this.apiVersion = apiVersion;
            this.version = version;
            this.interventionPoints = interventionPoints;
            this.budget = budget;
            this.tools = tools;
            this.contentRules = contentRules;
            this.resources = resources;
            this.dataLimits = dataLimits;
        }

        @JsonProperty("apiVersion")
        public String apiVersion() {
            return apiVersion;
        }

        @JsonProperty("version")
        public String version() {
            return version;
        }

        @JsonProperty("interventionPoints")
        public Set<InterventionPoint> interventionPoints() {
            return interventionPoints;
        }

        @JsonProperty("budget")
        public BudgetLimits budget() {
            return budget;
        }

        @JsonProperty("tools")
        public Map<String, ToolRule> tools() {
            return tools;
        }

        @JsonProperty("contentRules")
        public List<ContentRule> contentRules() {
            return contentRules;
        }

        @JsonProperty("resources")
        public Map<String, DataRule> resources() {
            return resources;
        }

        @JsonProperty("dataLimits")
        public DataLimits dataLimits() {
            return dataLimits;
        }

        @Override
        public boolean equals(Object value) {
            if (this == value) {
                return true;
            }
            if (value == null || getClass() != value.getClass()) {
                return false;
            }
            Document other = (Document) value;
            return Objects.equals(apiVersion, other.apiVersion)
                    && Objects.equals(version, other.version)
                    && Objects.equals(interventionPoints, other.interventionPoints)
                    && Objects.equals(budget, other.budget)
                    && Objects.equals(tools, other.tools)
                    && Objects.equals(contentRules, other.contentRules)
                    && Objects.equals(resources, other.resources)
                    && Objects.equals(dataLimits, other.dataLimits);
        }

        @Override
        public int hashCode() {
            int result = 0;
            result = 31 * result + Objects.hashCode(apiVersion);
            result = 31 * result + Objects.hashCode(version);
            result = 31 * result + Objects.hashCode(interventionPoints);
            result = 31 * result + Objects.hashCode(budget);
            result = 31 * result + Objects.hashCode(tools);
            result = 31 * result + Objects.hashCode(contentRules);
            result = 31 * result + Objects.hashCode(resources);
            result = 31 * result + Objects.hashCode(dataLimits);
            return result;
        }

        @Override
        public String toString() {
            return "Document[apiVersion="
                    + apiVersion
                    + ", version="
                    + version
                    + ", interventionPoints="
                    + interventionPoints
                    + ", budget="
                    + budget
                    + ", tools="
                    + tools
                    + ", contentRules="
                    + contentRules
                    + ", resources="
                    + resources
                    + ", dataLimits="
                    + dataLimits
                    + "]";
        }

        public Document(
                String apiVersion,
                String version,
                Set<InterventionPoint> points,
                BudgetLimits budget,
                Map<String, ToolRule> tools,
                List<ContentRule> rules) {
            this(
                    apiVersion,
                    version,
                    points,
                    budget,
                    tools,
                    rules,
                    Java8.map(),
                    DataLimits.defaults());
        }
    }

    private final Document doc;

    public StructuredPolicy(Document document) {
        if (!"agt.java/v1".equals(document.apiVersion())) {
            throw new IllegalArgumentException("Unsupported policy version");
        }
        Identifiers.require(document.version());
        Set<InterventionPoint> points =
                Java8.copySet(Objects.requireNonNull(document.interventionPoints()));
        Map<String, ToolRule> tools =
                Java8.copyMap(document.tools() == null ? Java8.map() : document.tools());
        tools.keySet().forEach(Identifiers::require);
        Map<String, DataRule> resources =
                Java8.copyMap(document.resources() == null ? Java8.map() : document.resources());
        resources.keySet().forEach(Identifiers::require);
        if (!resources.isEmpty()
                && !points.containsAll(
                        Java8.set(
                                InterventionPoint.PRE_DATA_ACCESS,
                                InterventionPoint.POST_DATA_ACCESS))) {
            throw new IllegalArgumentException("Missing data intervention points");
        }
        List<ContentRule> rules =
                Java8.copyList(
                        document.contentRules() == null ? Java8.list() : document.contentRules());
        Set<String> ids = new HashSet<>();
        for (ContentRule rule : rules) {
            if (!points.containsAll(rule.points()) || !ids.add(rule.id())) {
                throw new IllegalArgumentException("Invalid content rule binding");
            }
        }
        doc =
                new Document(
                        document.apiVersion(),
                        document.version(),
                        points,
                        Objects.requireNonNull(document.budget()),
                        tools,
                        rules,
                        resources,
                        document.dataLimits() == null
                                ? DataLimits.defaults()
                                : document.dataLimits());
    }

    public static StructuredPolicy fromPath(Path path) {
        try {
            if (Files.size(path) > 1_048_576) {
                throw new IllegalArgumentException();
            }
            return fromYaml(Java8.readString(path));
        } catch (Exception e) {
            throw new GovernanceException("invalid_policy");
        }
    }

    public static StructuredPolicy fromYaml(String yaml) {
        try {
            if (yaml == null || yaml.length() > 1_048_576) {
                throw new IllegalArgumentException();
            }
            YAMLFactory factory =
                    YAMLFactory.builder()
                            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .streamReadConstraints(
                                    StreamReadConstraints.builder()
                                            .maxNestingDepth(32)
                                            .maxStringLength(1_048_576)
                                            .maxNumberLength(128)
                                            .build())
                            .build();
            JsonMapper mapper =
                    JsonMapper.builder(factory)
                            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                            .build();
            return new StructuredPolicy(mapper.readValue(yaml, Document.class));
        } catch (Exception e) {
            throw new GovernanceException("invalid_policy");
        }
    }

    public static Builder builder(String version) {
        return new Builder(version);
    }

    @Override
    public String version() {
        return doc.version();
    }

    @Override
    public Set<InterventionPoint> interventionPoints() {
        return doc.interventionPoints();
    }

    public List<ContentRule> contentRules() {
        return doc.contentRules();
    }

    public BudgetLimits budget() {
        return doc.budget();
    }

    public DataLimits dataLimits() {
        return doc.dataLimits();
    }

    @Override
    public PolicyDecision evaluate(PolicyRequest r) {
        try {
            validateEffective(r);
        } catch (GovernanceException e) {
            return PolicyDecision.deny(e.code());
        }
        Object effective = r.target();
        String lastRule = "content_rewritten";
        for (ContentRule rule : doc.contentRules()) {
            if (rule.points().contains(r.point())) {
                effective = rule.transform(effective);
                lastRule = rule.id();
            }
        }
        if (!Objects.equals(effective, r.target())) {
            return PolicyDecision.transform(lastRule, effective);
        }
        if (r.point() == InterventionPoint.PRE_TOOL_CALL
                && doc.tools().get(r.toolName()).approvalRequired()) {
            return PolicyDecision.approval("tool_approval_required");
        }
        return PolicyDecision.allow();
    }

    @Override
    public void validateEffective(PolicyRequest r) {
        if (!doc.interventionPoints().contains(r.point())) {
            throw new GovernanceException("point_not_configured");
        }
        if (r.point() == InterventionPoint.PRE_TOOL_CALL) {
            ToolRule rule = doc.tools().get(r.toolName());
            if (rule == null) {
                throw new GovernanceException("tool_not_allowed");
            }
            if (!r.context().permissions().containsAll(rule.permissions())) {
                throw new GovernanceException("permission_denied");
            }
            rule.parameters().validate(r.target(), r.context());
        }
        if (r.point() == InterventionPoint.PRE_DATA_ACCESS
                || r.point() == InterventionPoint.POST_DATA_ACCESS) {
            Map<String, Object> resource = DataResource.resource(r.target());
            DataRule rule = doc.resources().get(resource.get("name"));
            if (rule == null) {
                throw new GovernanceException("data_resource_denied");
            }
            rule.validate(r);
        }
        for (ContentRule rule : doc.contentRules()) {
            if (rule.points().contains(r.point()) && rule.denies(r.target())) {
                throw new GovernanceException(rule.id());
            }
        }
    }

    /**
     * 改写后的工具仍可能需要审批；供宿主在执行前独立判断。
     *
     * @param r 策略请求
     * @return 是否需要审批
     */
    @Override
    public boolean requiresApproval(PolicyRequest r) {
        return r.point() == InterventionPoint.PRE_TOOL_CALL
                && doc.tools().get(r.toolName()) != null
                && doc.tools().get(r.toolName()).approvalRequired();
    }

    /**
     * 通过 Java API 组装不可变策略。
     */
    public static final class Builder {
        private final String version;
        private Set<InterventionPoint> points = InterventionPoint.lifecyclePoints();
        private BudgetLimits budget = BudgetLimits.defaults();
        private DataLimits dataLimits = DataLimits.defaults();
        private final Map<String, DataRule> resources = new LinkedHashMap<>();
        private final Map<String, ToolRule> tools = new LinkedHashMap<>();
        private final List<ContentRule> rules = new ArrayList<>();

        private Builder(String version) {
            this.version = version;
        }

        public Builder points(Set<InterventionPoint> points) {
            this.points = Java8.copySet(points);
            return this;
        }

        public Builder budget(BudgetLimits budget) {
            this.budget = budget;
            return this;
        }

        public Builder tool(String name, ToolRule rule) {
            if (tools.putIfAbsent(name, rule) != null) {
                throw new IllegalArgumentException("Duplicate tool");
            }
            return this;
        }

        public Builder resource(String name, DataRule rule) {
            if (resources.putIfAbsent(name, Objects.requireNonNull(rule)) != null) {
                throw new IllegalArgumentException("Duplicate resource");
            }
            EnumSet<InterventionPoint> expanded = EnumSet.copyOf(points);
            expanded.add(InterventionPoint.PRE_DATA_ACCESS);
            expanded.add(InterventionPoint.POST_DATA_ACCESS);
            points = expanded;
            return this;
        }

        public Builder dataLimits(DataLimits value) {
            dataLimits = Objects.requireNonNull(value);
            return this;
        }

        public Builder content(ContentRule rule) {
            rules.add(rule);
            return this;
        }

        public StructuredPolicy build() {
            return new StructuredPolicy(
                    new Document(
                            "agt.java/v1",
                            version,
                            points,
                            budget,
                            tools,
                            rules,
                            resources,
                            dataLimits));
        }
    }
}
