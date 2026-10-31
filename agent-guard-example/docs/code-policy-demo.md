# 纯 Java 策略案例：Fluent Builder

入口：[CodePolicyDemo.java](../src/main/java/example/CodePolicyDemo.java)。

业务侧只需要如下配置，不必再手动构造检查点集合、ContentRule、ParameterRule、Provider 和引擎：

```java
GuardEngine engine = AgtGuards.builder()
        .version("code-demo-v1")
        .denyText("DENIED")
        .maskInput("DEMO_PRIVATE_INPUT", "MASKED_INPUT")
        .maskToolOutput("DEMO_PRIVATE_TOOL", "MASKED_TOOL")
        .tool("lookup", tool -> tool
                .requirePermission("orders:read")
                .bindTenant("tenant")
                .requireString("orderId"))
        .build();
```

Imports：

```java
import org.agentguard.agt.AgtGuards;
import org.agentguard.engine.GuardEngine;
```

`createEngine()` 调用便捷 Builder 的 build；`createPolicy()` 保留为查看或高级组合策略的入口，返回 buildPolicy 的结果。两者使用同一份 configuration 方法，避免规则重复配置。

## 1. 运行

需要 JDK 17+、Maven 3.8+，以及支持工具调用的 OpenAI 兼容模型。

```shell
# 本次 SDK 新增了 AgtGuards，尚未安装最新制品时先执行。
./scripts/install-local.sh

# 编辑 .env 的模型地址、密钥和模型名后执行。
./scripts/run-code-policy.sh
```

等价命令：

```shell
mvn -nsu -Dexec.mainClass=example.CodePolicyDemo compile exec:java
```

也可直接在 IDE 中运行 CodePolicyDemo.main，工作目录设为项目根目录。第一个程序参数可指定其他 dotenv 文件。

模型仍使用 .env 或环境变量，安全策略完全由代码创建，不读取 YAML、不设置或使用策略路径、不扫描 Provider。代码不会在凭证缺失时回退到模拟模型。

## 2. 配置含义

| 配置 | 作用 |
| --- | --- |
| denyText | 自动覆盖六阶段，拒绝包含 DENIED 的内容 |
| maskInput | 进入后续处理前将用户输入标记改写为 MASKED_INPUT |
| maskToolOutput | 工具结果进入后续模型前替换敏感标记 |
| requirePermission | 工具调用者必须具备 orders:read |
| bindTenant | tenant 参数为必填字符串，必须等于可信 tenantId |
| requireString | orderId 必须存在并为字符串 |
| build | 自动创建 StructuredPolicy、AgtGuardProvider 和参考 GuardEngine |

bindTenant 已声明 tenant 为必填字符串，不要再 requireString("tenant")。字符串最大长度沿用原 ParameterRule.string 默认值；未额外实现订单格式校验。

规则 ID 自动生成。Demo 对输入拒绝验证阶段和风险发现；工具拒绝仍验证 context_binding_denied、permission_denied 等明确原因。若业务依赖稳定内容规则 ID，使用底层 contentRule 方法配置。

这些都是字面演示规则，不提供通用语义注入检测或自动 PII 识别。

## 3. 传给 LangChain4j

共享 AI Service 组装位于 [GuardDemo.java](../src/main/java/example/GuardDemo.java) 的 assistant 方法：

```java
.guardContextFactory(invocation -> GuardContext.builder()
        .traceId(UUID.randomUUID().toString())
        .agentId("order-demo")
        .tenantId("tenant-A")
        .userId("demo-user")
        .attributes(Map.of(StandardAttributes.PERMISSIONS, permissions))
        .build())
.guardEngine(engine)
.build();
```

以上为构建器链片段。身份与权限必须由宿主提供，不能从提示词或模型参数推导。

显式 engine 非空时，LangChain4j 直接使用它并跳过自动工厂。SDK 的 SPI 描述文件可以继续留在 classpath；现有 YAML 文件也可保留用于另一个案例。

## 4. 与原案例对照

| 项目 | GuardDemo | CodePolicyDemo |
| --- | --- | --- |
| 安全策略 | YAML 文件 | AgtGuards Fluent Builder |
| 引擎创建 | 显式 guardAutoDiscovery(true) 后由 SPI 工厂创建 | Builder.build |
| 传给 AiServices 的引擎 | null，构建时发现 | 显式非空实例 |
| 策略路径配置 | 必需 | 不使用 |
| LLM 配置 | .env / 环境变量 | 同左 |
| 执行场景 | 共享 runScenarios | 同左 |
| 启动脚本 | run.sh | run-code-policy.sh |

两者后续的请求上下文、六阶段执行和阻断/改写机制相同。原始 StructuredPolicy / ContentRule API 继续保留，便捷层没有替换底层实现。

## 5. 高级配置与错误检查

- `optionalString("note")`：字段可缺失，存在时校验字符串。
- `denyTextAt(GuardStage.MODEL_OUTPUT, "FORBIDDEN")`：只在模型输出拒绝。
- `maskAt(stage, source, replacement)`：指定阶段替换，服从原 Provider 改写边界。
- `parameter(name, parameterRule, mandatory)`：复用底层的数字、枚举等约束。
- `contentRule(rule)`、`toolRule(name, rule)`：接入已有规则。
- `buildPolicy()`、`buildProvider()`：用于高级组合及监听器配置。

空配置、重复工具、重复参数、重复权限、重复自定义规则 ID，以及同源字符串在重叠阶段的重复替换会明确报错。新建 Builder 后单线程配置；已构建的策略和引擎不随 Builder 后续修改而变化。

未注册的工具依然被拒绝。身份校验及参数改写后的再次授权校验保持不变。当前接入仍不执行审批、预算或流式治理。

## 6. 验证与 Debug

```shell
mvn -nsu test
```

同一组本地 HTTP 协议测试分别运行 YAML/SPI 和 Fluent 代码入口，验证输入拒绝、输入与工具结果脱敏、正常工具循环、跨租户和权限阻断。

纯代码测试故意设置不存在的 YAML 系统属性路径，以及空的 LlmConfig 策略路径。任何错误的自动工厂初始化或策略路径读取都会使测试失败；结束后恢复原系统属性。

本地测试不消耗外部模型额度，不等于真实 LLM 已验证。填写 .env 后运行代码入口才是外部模型演示。模型未生成期望工具调用时会断言失败，不能把自然语言拒绝当成工具授权测试通过。

建议断点顺序：CodePolicyDemo.configuration → AgtGuards.Builder.buildPolicy → buildProvider → build → GuardDemo.assistant → DefaultGuardEngine.check → AgtGuardProvider.PolicyGuard.check。

LangChain4j 的 AgentGuardAutoConfiguration 此时会因为显式 engine 非空直接返回。后续流程参见[调用链文档](langchain4j-agt-call-chain.md)和[新框架接入指南](agent-framework-integration-guide.md)。
