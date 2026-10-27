# LangChain4j 与 Agent Guard AGT 调用链及调试指南

本文沿着订单查询 Demo，说明从 LangChain4j 的 SPI 自动装配到 AGT 策略评估，再到实际阻断和改写的完整流程。

适用于已经接入 Agent Guard 六阶段检查点的本地 LangChain4j 版本。未经适配的官方发行版不会因为引入 SDK 自动获得这些检查点。本文重点描述非流式同步工具调用；异步调用有对应入口。

## 1. 各层解决什么问题

| 层次 | 职责 | 关键类型 |
| --- | --- | --- |
| LangChain4j 适配层 | 在真实调用边界触发检查，转换输入输出，落实改写和终止 | `AgentGuardAutoConfiguration`、`AgentGuardBridge`、`GuardedChatModel`、`ToolService` |
| agent-guard-spec | 提供框架与安全 SDK 共享的接口、数据模型和异常契约 | `GuardEngine`、`GuardContext`、`GuardDecision`、`GuardOutcome` |
| agent-guard-core | 编排 Guard，处理排序、执行模式、检测故障和审计记录 | `DefaultGuardEngineFactory`、`DefaultGuardEngine` |
| agent-guard-agt | 将中立护栏接口桥接到 AGT 策略评估 | `AgtGuardEngineFactory`、`AgtGuardProvider`、`AgtPayloads` |
| agt-core | 判断具体内容规则、工具权限和参数约束 | `StructuredPolicy`、`ToolRule`、`ParameterRule` |

LangChain4j 的生产代码只依赖 spec。AGT SDK 传递引入 core 和 agt-core；框架无需引用具体 SDK 类。

## 2. 构建阶段：两次 SPI 发现

业务代码构建普通 AI Service：

```text
AiServices.builder(Assistant.class)
    .chatModel(model)
    .tools(tools)
    .guardAutoDiscovery(true)
    .guardContextFactory(...)
    .build()
```

上面是调用顺序示意，省略了上下文工厂的具体实现。自动装配路径显式开启 `guardAutoDiscovery(true)`，不配置 `guardEngine`；框架默认关闭发现。

构建调用链：

```text
DefaultAiServices.build()
└── validate()
    ├── AgentGuardAutoConfiguration.configure(context)
    │   ├── 第一次 SPI：发现 GuardEngineFactory
    │   ├── AgtGuardEngineFactory.create()
    │   │   └── DefaultGuardEngineFactory.create()
    │   │       ├── 第二次 SPI：发现 GuardProvider
    │   │       ├── 创建 AgtGuardProvider
    │   │       │   ├── 读取 YAML，创建 StructuredPolicy
    │   │       │   └── 为六个阶段分别创建 PolicyGuard
    │   │       ├── 汇总各 Provider.guards()
    │   │       └── new DefaultGuardEngine(guards, null, null)
    │   │           └── 按 stage 分组、按 order/id 排序
    │   └── 将引擎保存到 context.guardEngine
    └── 将引擎交给 ToolService
```

### 2.1 第一次 SPI：找引擎工厂

LangChain4j 的 `AgentGuardAutoConfiguration.configure()` 通过已有的 `ServiceHelper.loadFactories(GuardEngineFactory.class)` 发现工厂。

AGT SDK 注册文件：

- [GuardEngineFactory 描述文件](../agent-guard-agt/src/main/resources/META-INF/services/org.agentguard.spi.GuardEngineFactory)
- 内容：`org.agentguard.agt.AgtGuardEngineFactory`

`AgtGuardEngineFactory.create()` 委托给参考工厂：

```java
@Override
public GuardEngine create() {
    return new DefaultGuardEngineFactory().create();
}
```

自动发现默认关闭。只有手动设置 guardAutoDiscovery(true) 且没有显式引擎时扫描；否则跳过。没有工厂则保持未启用护栏的行为；存在多个不同工厂则构建失败，不随机选一个。发现、初始化错误也会使构建失败。

### 2.2 第二次 SPI：找规则提供者

[DefaultGuardEngineFactory.create()](../agent-guard-core/src/main/java/org/agentguard/core/DefaultGuardEngineFactory.java) 内部调用 `ServiceLoader.load(GuardProvider.class, loader)`。

AGT SDK 的另一份注册文件：

- [GuardProvider 描述文件](../agent-guard-agt/src/main/resources/META-INF/services/org.agentguard.spi.GuardProvider)
- 内容：`org.agentguard.agt.AgtGuardProvider`

两份 SPI 的区别：工厂负责返回引擎，Provider 负责返回检测规则。Provider 本身不是引擎。

默认工厂会按 Provider 的 `order()` 排序，收集 `guards()`，最后构建 `DefaultGuardEngine`。没有任何 Guard 时初始化失败，避免把空规则当成安全能力已启用。

### 2.3 创建 AGT 的六个 Guard

[AgtGuardProvider](../agent-guard-agt/src/main/java/org/agentguard/agt/AgtGuardProvider.java) 无参构造器读取策略路径，创建 `StructuredPolicy`。系统属性 `agentguard.agt.policy-path` 优先于环境变量 `AGENTGUARD_AGT_POLICY_PATH`。

它为每个 `GuardStage` 创建一个内部 `PolicyGuard`，六个 Guard 共享同一个策略评估器，但绑定不同的检查点：

| GuardStage | AGT InterventionPoint |
| --- | --- |
| AGENT_INPUT | INPUT |
| MODEL_INPUT | PRE_MODEL_CALL |
| MODEL_OUTPUT | POST_MODEL_CALL |
| TOOL_INPUT | PRE_TOOL_CALL |
| TOOL_OUTPUT | POST_TOOL_CALL |
| AGENT_OUTPUT | OUTPUT |

策略必须配置全部六个检查点。每个 AGT Guard 的 `order()` 为 1000，不意味着组合其他 Provider 后 AGT 永远最后执行。

### 2.4 引擎建立阶段索引

[DefaultGuardEngine.indexGuards()](../agent-guard-core/src/main/java/org/agentguard/core/DefaultGuardEngine.java) 校验 Guard 非空、阶段合法、ID 非空白且全局唯一，并组织为：

```text
Map<GuardStage, List<Guard<?>>>
├── AGENT_INPUT  → 当前阶段的 Guard 列表
├── MODEL_INPUT  → 当前阶段的 Guard 列表
├── MODEL_OUTPUT → 当前阶段的 Guard 列表
├── TOOL_INPUT   → 当前阶段的 Guard 列表
├── TOOL_OUTPUT  → 当前阶段的 Guard 列表
└── AGENT_OUTPUT → 当前阶段的 Guard 列表
```

阶段内按 `order` 升序，再按 `id` 字典序执行。

引擎在 AI Service 构建上下文中初始化并复用，不在每次请求重新扫描 SPI 或读取 YAML，也不是全局静态单例。

## 3. 请求阶段：创建上下文

调用 `assistant.chat(...)` 后，AI Service 的代理调用路径会执行 `AgentGuardBridge.attach()`：

1. 调用业务配置的 `guardContextFactory` 创建根 `GuardContext`。
2. 创建 `AgentGuardSession`，持有根上下文和本次调用状态。
3. 将会话放入 `InvocationContext.managedParameters`，供后续模型、工具阶段取得。

```text
InvocationContext：一次 LangChain4j 调用
└── AgentGuardSession：本次调用的护栏状态
    ├── root GuardContext：可信身份、权限、共享 state
    ├── modelCalls：模型调用轮次计数
    └── allowedTools：本轮模型请求允许的工具集合
```

引擎由 AI Service 复用；上下文与护栏会话按请求创建。`withStage()`、`withIteration()` 派生的上下文共享同一个 `state`，不同请求的根上下文应保持隔离。

不要使用 `root.toBuilder().build()` 代替阶段派生：它会创建新的 state。state 使用并发 Map，但复合操作仍需保证原子性。

可信身份由宿主认证结果注入，不从用户提示词或模型工具参数推导。当前 AGT Provider 将 `agentId`、`tenantId`、`userId` 映射到 AGT 的 `agentId`、`tenantId`、`principalId`，权限取自 `StandardAttributes.PERMISSIONS`。

## 4. 一次正常工具查询的完整路径

```text
assistant.chat(...)
    │
    ├── AGENT_INPUT：检查用户输入，必要时改写
    │
    ├── MODEL_INPUT：检查完整模型请求
    ├── 调用真实 LLM
    ├── MODEL_OUTPUT：检查模型文字和工具调用
    │
    ├── TOOL_INPUT：检查工具权限、租户与参数
    ├── 执行 lookup(...)
    ├── TOOL_OUTPUT：检查并可能脱敏工具结果
    │
    ├── MODEL_INPUT：检查包含工具结果的新请求
    ├── 再次调用真实 LLM
    ├── MODEL_OUTPUT：检查模型回答
    │
    └── AGENT_OUTPUT：检查最终业务返回内容
```

模型和工具阶段可能执行多次，Agent 输入与最终输出位于请求边界。模型不调用工具时，不会执行工具阶段。

| 阶段 | LangChain4j 源码入口 | 行为 |
| --- | --- | --- |
| AGENT_INPUT | `DefaultAiServices` 的请求执行路径 | 用户消息准备后、RAG 与模型处理前检查，并应用输入改写 |
| MODEL_INPUT | `GuardedChatModel.checkRequest()` | 检查完整请求，应用改写，记录本轮允许工具 |
| MODEL_OUTPUT | `GuardedChatModel.checkResponse()` | 检查完整响应，应用文字及工具调用改写 |
| TOOL_INPUT | `ToolService` 的工具执行路径 | 先校验本轮允许工具，再检查参数及身份，允许后才执行工具 |
| TOOL_OUTPUT | `AgentGuardBridge.checkToolOutput()` | 检查工具结果，将有效结果交给后续模型流程 |
| AGENT_OUTPUT | `DefaultAiServices` 的最终返回路径 | 检查业务内容，应用改写后返回 |

`AgentGuardBridge.chatModel()` 用 `GuardedChatModel` 包装真实模型。同步调用结构是：

```text
checkRequest(request)
    → delegate.chat(检查后的请求)
    → checkResponse(response)
```

工具循环中的后续模型调用和相关输出 Guardrail 重试也使用该包装入口。异步模型路径由 `chatAsync()` 对应处理；本文的断点建议优先按同步 Demo 跟踪。

## 5. 一次检查如何进入 AGT

```text
LangChain4j 原生对象
    ↓ AgentGuardBridge 转换
spec 的 AgentInput / ModelRequest / ToolCall 等
    ↓ GuardEngine.checkXxx(...)
DefaultGuardEngine.check(stage, payload, context)
    ↓ 找到当前阶段 Guard
AgtGuardProvider.PolicyGuard.check(...)
    ↓ AgtPayloads.project(...)
AGT PolicyRequest
    ↓ StructuredPolicy.evaluate(...)
AGT PolicyDecision
    ↓ PolicyGuard 转换
spec GuardDecision
    ↓ Engine 汇总
spec GuardOutcome
    ↓ LangChain4j 应用结果
继续执行、改写或终止
```

两次转换有不同职责：

- `AgentGuardBridge`：LangChain4j 对象与 spec 的中立数据模型之间转换。
- [AgtPayloads](../agent-guard-agt/src/main/java/org/agentguard/agt/AgtPayloads.java)：spec 数据与 AGT 可检查的数据之间转换，改写后恢复原阶段类型。

### 5.1 DefaultGuardEngine：通用编排

`DefaultGuardEngine.check()` 执行：

1. 校验阶段、上下文和 payload 类型。
2. 获取当前阶段的 Guard 列表。
3. 解析 Guard 默认策略以及动态策略，处理执行模式和 `supports()`。
4. 调用 `guard.check(currentPayload, context)`。
5. 在强制执行模式下，将 MODIFY 的结果交给后续 Guard。
6. 遇到生效的 BLOCK 或 REJECT 时短路后续 Guard。
7. 汇总动作、发现和执行记录；有监听器时发布聚合事件。
8. 返回 `GuardOutcome`。

自动装配的参考工厂没有配置事件监听器，但执行结果仍包含 GuardRecord。没有配置 Guard 的阶段也会形成空记录的阶段结果；有监听器时会发送相应事件。

### 5.2 PolicyGuard：策略桥接

`AgtGuardProvider.PolicyGuard.check()` 执行：

1. 将 `GuardContext` 映射为 AGT `TrustedContext`。
2. 将阶段 payload 投影为策略目标，构建 `PolicyRequest`。
3. 调用 `PolicyEvaluator.evaluate()`；当前 YAML 使用 `StructuredPolicy`。
4. 将拒绝转换为 `GuardDecision.block()`，将审批需求明确阻断为 `approval_unsupported`。
5. 对有效数据调用 `validateEffective()`，并再次检查审批需求。
6. 将改写结果恢复为原阶段类型，返回 MODIFY；否则返回 PASS。

改写后的再次校验是为了避免替换后的参数绕过权限或约束。组合其他 Provider 时，仍需要保证后续改写不会使已完成的授权失效。

### 5.3 两种策略不要混淆

| 类型 | 负责什么 | 示例 |
| --- | --- | --- |
| spec 的 `GuardPolicy` | 如何执行 Guard | ENFORCE、OBSERVE、DISABLED；FAIL_OPEN、FAIL_CLOSED |
| AGT 的 `StructuredPolicy` | 具体判断什么 | 拒绝标记、工具权限、参数与可信租户绑定、字符串替换 |

观察模式会保留输入，将命中的改写或拒绝降为观察；故障放行仅针对检测故障，不能把显式违规 BLOCK 当成故障放行。当前默认是 ENFORCE / FAIL_CLOSED。

## 6. 跨租户请求如何被阻断

Demo 宿主注入可信租户 `tenant-A`，用户要求查询 `tenant-B`。假设模型确实生成了相应工具调用：

```text
lookup(tenant="tenant-B", orderId="ORD-9999")
    ↓ ToolService：TOOL_INPUT
DefaultGuardEngine.check(...)
    ↓ PolicyGuard.check(...)
StructuredPolicy.evaluate(...)
    ↓ validateEffective(...)
ParameterRule.validate(...)：equalsContext = tenantId
    ↓ tenant-B != 可信 tenant-A
GovernanceException("context_binding_denied")
    ↓ StructuredPolicy 转为 DENY
PolicyGuard 转为 GuardDecision.BLOCK
    ↓ Engine 汇总
GuardOutcome
    ↓ ToolService 调用 payloadOrThrow()
GuardBlockedException
```

业务工具方法体尚未执行，因此工具计数没有增加。若模型没有生成工具调用，则不能把其自然语言拒绝视为工具授权链路已验证。

[GuardOutcome.payloadOrThrow()](../agent-guard-spec/src/main/java/org/agentguard/engine/GuardOutcome.java) 负责把执行结果落实为返回值或异常：

| 结果 | 默认行为 |
| --- | --- |
| PASS / OBSERVE / MODIFY | 返回当前有效 payload |
| 违规 BLOCK | 抛 `GuardBlockedException` |
| 检测故障按 FAIL_CLOSED 阻断 | 抛 `GuardExecutionException`，保留 cause |
| REJECT | 直接调用该方法会抛阻断异常；工具适配路径可单独使用 reply 替代执行/结果 |

Engine 返回 BLOCK 决议，并不等于已经替框架停止所有操作。框架适配层必须落实终止；并行工具中其他已经启动的操作也不会自动回滚。

## 7. 工具结果改写如何真正生效

以工具返回 `DEMO_PRIVATE_TOOL`、策略替换为 `MASKED_TOOL` 为例：

```text
业务工具返回原始内容
    → ToolService 进入 TOOL_OUTPUT
    → PolicyGuard 将内容交给 AGT
    → StructuredPolicy 返回 TRANSFORM
    → AgtPayloads 恢复为 spec ToolResult
    → Engine 返回 MODIFY 的 GuardOutcome
    → AgentGuardBridge 转回 LangChain4j ToolExecutionResult
    → 后续模型请求使用脱敏后的工具内容
```

验证时应查看下一次实际模型请求，而不仅查看 Finding 或日志中是否出现 MODIFY。
工具输出检查发生在工具执行之后，无法撤销工具已经造成的副作用。

## 8. 推荐断点与观察变量

| 顺序 | 断点 | 观察重点 |
| --- | --- | --- |
| 1 | `AgentGuardAutoConfiguration.configure()` | 工厂列表、是否已有显式引擎、最终 context.guardEngine |
| 2 | `DefaultGuardEngineFactory.create()` | 第二次 SPI 的 Provider 列表及汇总 guards |
| 3 | `AgtGuardProvider` 构造器 | 策略路径、六个 PolicyGuard、共享 evaluator |
| 4 | `DefaultGuardEngine.indexGuards()` | guardsByStage 和阶段内顺序 |
| 5 | `AgentGuardBridge.attach()` | 请求根上下文、可信身份和共享 state |
| 6 | `GuardedChatModel.checkRequest()` | 发送给模型前的完整请求、轮次与 allowedTools |
| 7 | `DefaultGuardEngine.check()` | stage、currentPayload、decidedAction、effectiveAction |
| 8 | `PolicyGuard.check()` | projection、PolicyRequest、AGT decision、恢复后的结果 |
| 9 | `StructuredPolicy.validateEffective()` / `ParameterRule.validate()` | 工具规则、权限、参数及租户绑定 |
| 10 | `GuardOutcome.payloadOrThrow()` | 正常返回、违规异常或执行故障异常 |

`DefaultGuardEngine.check()` 调用较频繁，调试工具授权时可设置条件断点：

```java
stage == org.agentguard.GuardStage.TOOL_INPUT
```

在 AGT 中也可以按 `request.point()` 或工具名筛选。ToolService 同时有同步和异步路径，断点应按实际运行路径设置。

## 9. 源码阅读顺序

Agent Guard 仓库中的源码可直接打开：

1. [AgtGuardEngineFactory](../agent-guard-agt/src/main/java/org/agentguard/agt/AgtGuardEngineFactory.java)
2. [DefaultGuardEngineFactory](../agent-guard-core/src/main/java/org/agentguard/core/DefaultGuardEngineFactory.java)
3. [AgtGuardProvider](../agent-guard-agt/src/main/java/org/agentguard/agt/AgtGuardProvider.java)
4. [DefaultGuardEngine](../agent-guard-core/src/main/java/org/agentguard/core/DefaultGuardEngine.java)
5. [GuardEngine](../agent-guard-spec/src/main/java/org/agentguard/engine/GuardEngine.java)
6. [AgtPayloads](../agent-guard-agt/src/main/java/org/agentguard/agt/AgtPayloads.java)
7. [StructuredPolicy](../agt-core/src/main/java/io/agt/core/policy/StructuredPolicy.java)
8. [ParameterRule](../agt-core/src/main/java/io/agt/core/policy/ParameterRule.java)
9. [GuardOutcome](../agent-guard-spec/src/main/java/org/agentguard/engine/GuardOutcome.java)

LangChain4j 仓库中按以下相对源码路径定位，不依赖易变的行号：

```text
langchain4j/src/main/java/dev/langchain4j/service/
├── DefaultAiServices.java
├── guard/
│   ├── AgentGuardAutoConfiguration.java
│   ├── AgentGuardBridge.java
│   ├── AgentGuardSession.java
│   └── GuardedChatModel.java
└── tool/
    └── ToolService.java
```

## 10. 显式配置与验证边界

用代码构建 `StructuredPolicy` 并通过 `.guardEngine(engine)` 传入时，会跳过本文的自动发现流程；请求上下文、六阶段检查、策略评估及结果应用仍走相同机制。

也可在自定义 SDK 工厂中用代码创建策略和引擎，保留 SPI 使用体验；需要避免与现有工厂重复注册造成冲突。

本文基于源码梳理，不代表重新执行了真实 LLM 测试。案例的本地 HTTP 协议测试可以验证客户端、SPI、工具循环和改写/阻断，但不等于外部模型验证。真实 LLM 验证需要填写案例的模型配置并实际运行。

当前 AGT 桥接仅复用策略评估，没有启动 `AgentControl`，不能据此推断已接入会话预算、审批、取消、数据访问和流式治理。

相关文档：[设计契约](design-contract.md)、[SDK 自动装配指南](sdk-auto-configuration.md)、[AGT 接入说明](agt-implementation.md)。
