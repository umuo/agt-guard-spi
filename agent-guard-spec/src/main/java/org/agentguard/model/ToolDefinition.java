package org.agentguard.model;

import org.agentguard.util.Immutables;

import java.util.Map;

/**
 * 暴露给 LLM 的工具定义。MODEL_INPUT 阶段可据此做"工具可见性"检查。
 */
public final class ToolDefinition {

    private final String name;
    private final String description;
    private final String parametersSchema;
    private final Map<String, Object> metadata;

    private ToolDefinition(String name, String description, String parametersSchema, Map<String, Object> metadata) {
        this.name = Immutables.requireNonNull(name, "name");
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.metadata = Immutables.map(metadata);
    }

    public static ToolDefinition of(String name, String description, String parametersSchema) {
        return new ToolDefinition(name, description, parametersSchema, null);
    }

    public static ToolDefinition of(String name, String description, String parametersSchema,
                                    Map<String, Object> metadata) {
        return new ToolDefinition(name, description, parametersSchema, metadata);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** 参数 JSON Schema 字符串。 */
    public String parametersSchema() {
        return parametersSchema;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "ToolDefinition{name=" + name + '}';
    }
}
