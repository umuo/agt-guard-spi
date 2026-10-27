# Agent Guard

AGT 策略接入：JDK 8+ 下执行 `mvn -Pagt verify`，见 [AGT 接入使用说明](docs/agt-implementation.md)。
默认构建包含 spec/core、AGT core 和 AGT Provider，均支持 Java 8；`-Pagt` 保留为兼容入口。

框架无关的 Java Agent 安全护栏 SPI，覆盖用户输入、模型输入输出、工具输入输出和 Agent 最终输出。

- `agent-guard-spec`：JDK 8 数据模型、SPI、Engine 门面、策略、事件与异常。
- `agent-guard-core`：排序、链式改写、观察模式、失败策略与审计的参考实现。

运行验证：

```shell
mvn test
```

SDK 实现对应阶段的 Guard，通过 Builder 注册到 Engine。发现违规返回 `GuardDecision.block(...)`，普通执行故障交由失败策略处理；框架适配器必须应用修改后的 payload，并通过 `payloadOrThrow()` 落实终止。

具体分层、动作语义、已支持能力及验收要求见 [设计契约](docs/design-contract.md)。当前提供 AGT 策略 Provider；LangChain4j 接入原型位于独立框架仓库。TokenStream 流式护栏支持显式完整缓存或有界字面窗口模式，见 [流式契约](docs/streaming-design.md)；媒体与 Spring AI 适配尚未实现。

异常契约：违规阻断抛 `GuardBlockedException`；检测故障按 `FAIL_CLOSED` 阻断时抛 `GuardExecutionException`，保留原因。故障审计记录的 `decidedAction()` 为 `null`，使用 `error()` 区分故障与检测决议。


SDK 可通过 `GuardEngineFactory` 发布引擎，实现业务应用引入后自动生效。采用参考引擎时，注册 `DefaultGuardEngineFactory` 和自己的 `GuardProvider` 即可；LangChain4j 不需要依赖具体 SDK 或 core。完整的代码、资源文件和配置规则见 [SDK 自动装配指南](docs/sdk-auto-configuration.md)。

调用链与源码调试：[LangChain4j 与 Agent Guard AGT 调用链及调试指南](docs/langchain4j-agt-call-chain.md)。

纯代码便捷配置：[AGT Fluent Builder](docs/agt-fluent-api.md)，使用 `AgtGuards.builder()` 声明内容、脱敏和工具规则，`build()` 直接得到引擎。
