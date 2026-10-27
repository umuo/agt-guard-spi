# AGT 纯代码护栏：Fluent Builder

`AgtGuards` 为常见业务规则提供便捷配置，自动创建六阶段策略、Provider 和参考引擎。它复用现有 AGT 实现，不新增依赖，也不改变 YAML/SPI 接入契约。

## 目标与验收

目标是让业务代码无需手动拼接 `ContentRule`、检查点集合、工具对象参数规则和引擎装配。验收覆盖六阶段拒绝、定点改写、工具权限、租户绑定、改写后复查、不可变快照、非法配置、Java 8 兼容，以及现有 YAML/SPI 与示例回归。

实现选择直接封装本工程已有的 `StructuredPolicy` / `ParameterRule`，没有引入另一个策略 DSL 或表达式引擎。这样沿用现有校验和异常行为，便捷层只负责配置组合。

## 最小使用

业务应用依赖 `org.agentguard:agent-guard-agt`，然后创建引擎：

```java
GuardEngine engine = AgtGuards.builder()
        .denyText("DENIED")
        .maskInput("DEMO_PRIVATE_INPUT", "MASKED_INPUT")
        .maskToolOutput("DEMO_PRIVATE_TOOL", "MASKED_TOOL")
        .tool("lookup", tool -> tool
                .requirePermission("orders:read")
                .bindTenant("tenant")
                .requireString("orderId"))
        .build();
```

核心 imports：

```java
import org.agentguard.agt.AgtGuards;
import org.agentguard.engine.GuardEngine;
```

把引擎通过框架的 `.guardEngine(engine)` 显式配置即可。此路径不读取 YAML、不设置策略路径、不扫描 SPI。SDK 的 SPI 注册仍然保留；显式引擎优先，由框架跳过自动工厂。

Builder 使用 Java 8 API。新建 Builder 后单线程配置，构建出的策略、Provider 与参考引擎按原有线程安全契约复用；每次请求仍需要独立 GuardContext。

## 常用方法

| 方法 | 语义 |
| --- | --- |
| `version(value)` | 策略版本，默认 code-v1；遵循 AGT 标识符校验 |
| `denyText(values...)` | 六个检查阶段拒绝指定字面字符串；是匹配规则，不是语义检测 |
| `denyTextAt(stage, values...)` | 仅在指定 GuardStage 拒绝 |
| `maskInput(source, replacement)` | 在 AGENT_INPUT 替换字符串 |
| `maskToolOutput(source, replacement)` | 在 TOOL_OUTPUT 替换字符串 |
| `maskAt(stage, source, replacement)` | 指定阶段替换，受原 Provider 改写边界约束 |
| `tool(name, configure)` | 声明工具规则并使用 ToolBuilder 配置 |
| `contentRule(rule)` | 接入现有底层 ContentRule |
| `toolRule(name, rule)` | 接入现有底层 ToolRule |
| `buildPolicy()` | 返回 StructuredPolicy，便于检查、组合或自定义工厂 |
| `buildProvider()` | 返回已配置的 AgtGuardProvider，便于组合其他 Guard |
| `build()` | 返回已完成装配的 GuardEngine |

`denyText` 支持多个标记，例如 `.denyText("DENIED", "FORBIDDEN")`。字符串按原值匹配，不去除空白；null、空字符串及一次调用中的重复拒绝标记会被拒绝。

## 工具与参数

```java
.tool("lookup", tool -> tool
        .requirePermission("orders:read", "catalog:read")
        .bindTenant("tenant")
        .requireString("orderId")
        .optionalString("note"))
```

- `requirePermission` 声明全部必需权限，权限由可信 GuardContext 注入。
- `bindTenant("tenant")` 自动声明必填字符串，并要求它等于可信 tenantId。不要再次 `requireString("tenant")`，那是重复参数。
- `requireString` 声明必填字符串；`optionalString` 允许字段缺失，但存在时仍校验类型。
- 字符串限制沿用 ParameterRule.string 的默认约束（当前最大 4096 个 Unicode code point），没有额外承诺非空或订单格式校验。
- 高级类型或范围通过 `parameter(name, parameterRule, mandatory)` 传入原有 ParameterRule。
- 常用工具 Builder 默认不要求审批；需要原有审批配置等高级行为时使用 `toolRule`，审批需求仍按当前 Provider 契约明确阻断。

未注册的工具仍按原 AGT 契约拒绝。只配置内容规则也不意味着任意工具自动获准执行。

身份校验、工具参数改写后的再次校验、工具名和调用 ID 不可改写等原有边界不变。

## 配置错误与规则顺序

构建器会拒绝：

- 空 Builder 构建，避免空配置伪装为已启用护栏。
- 重复工具名、重复参数名、重复权限、重复自定义内容规则 ID。
- 同一个源字符串在重叠阶段重复声明替换，不论目标相同或不同。
- 空源字符串、null 替换值、源和目标相同的无效替换。
- 自定义内容规则绑定六阶段之外的检查点。

同源字符串在不同阶段可以使用不同目标。不同源字符串的替换按声明顺序执行，可能发生串联，例如 A→B 后 B→C；调用方需按预期顺序配置。现有 AGT 先做拒绝校验，再执行替换，并复查有效结果，不能通过先配置替换来绕过拒绝。

每条便捷内容规则自动生成 `agt.fluent.rule.N` 标识，构建器会避开已添加的自定义 ID。该编号跟声明顺序有关，不应当作跨版本稳定的业务错误码。需要稳定 ID 时使用 `contentRule` 显式传入。

`.buildPolicy()`、`.buildProvider()`、`.build()` 每次构建独立快照。继续修改 Builder 不影响已创建的引擎；工具回调结束时就已生成规则快照，后续修改捕获的 ToolBuilder 不会改变已注册工具。

## 高级组合

便捷入口不会阻止使用原有参考引擎配置，例如：

```java
GuardEngine engine = new GuardEngineBuilder()
        .addProvider(AgtGuards.builder()
                .denyText("DENIED")
                .buildProvider())
        .addListener(event -> recordAudit(event))
        .build();
```

上面的 `recordAudit` 由宿主实现。也可在自定义 GuardEngineFactory 的 create 方法中调用便捷 Builder，通过自定义 SDK 工厂发布；避免与现有工厂重复注册。

## 边界与验证

此便捷层不新增检测算法、模型重试、审批、预算、流式或数据访问治理。StructuredPolicy 仍持有原来的默认预算数据，但 Provider 不执行预算记账。maskAt 只在原 Provider 支持的投影和改写边界内生效，不支持的载荷或改写明确失败。

新增 AgtGuardsTest 使用真实策略与引擎验证六阶段拒绝、输入与工具结果改写、权限、租户、必填/可选参数、改写后授权复查、身份故障、快照和配置冲突。现有 Provider/SPI 测试继续验证原有接入方式。

可在 JDK 8+ 下运行 `mvn verify`；JDK 17+ 自动启用 Spotless 检查。仅编译通过或设置 target=8 不等于实际 JDK 8 运行验证。

相关文档：[AGT 接入说明](agt-implementation.md)、[自动装配指南](sdk-auto-configuration.md)、[设计契约](design-contract.md)。
