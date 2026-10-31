# 新 Agent 框架接入 Agent Guard：生命周期与代码指南

本文面向 Agent 框架维护者，说明如何在框架内部接入安全护栏。目标是让框架只依赖 `agent-guard-spec`，由业务应用选择具体 SDK，同时确保检查结果实际改变模型请求、工具执行和最终返回。

前置阅读：[LangChain4j 与 Agent Guard AGT 调用链及调试指南](langchain4j-agt-call-chain.md)。前者解释已有实现，本文说明新框架该在哪里增加代码。

## 1. 接入目标与范围

完成接入后，应同时满足：

- 显式设置引擎或引入支持 SPI 的 SDK，都能在构建阶段装配。
- 每次请求创建独立上下文，在模型循环、工具调用和异步任务中传递。
- 在六个生命周期位置执行检查，改写后的值真正进入下一步。
- 输入或工具被阻断时，相应模型调用或工具操作不会发生。
- 不支持的转换、媒体或流式模式明确失败，不通过抽取部分文本假装完成检查。

本文提供 Java 17 的同步、非流式接入骨架。spec 本身兼容 Java 8；示例使用 Java 17 的 record、集合便利方法是为了简化教学，并非提高 SDK 的最低运行要求。

可编译源码：

- [FrameworkGuardAdapter.java](examples/framework-integration/FrameworkGuardAdapter.java)：SPI 发现、请求会话及六阶段执行封装。
- [FrameworkGuardAdapterSmoke.java](examples/framework-integration/FrameworkGuardAdapterSmoke.java)：仅依赖 spec 的冒烟验证，不调用真实模型。

这两份文件是接入参考，不是替代框架运行时的新 Agent 产品，也不包含完整的模型协议、RAG、记忆或业务工具实现。

## 2. 依赖与职责分层

### 2.1 框架仅依赖 spec

在框架的适配模块添加：

```xml
<dependency>
    <groupId>org.agentguard</groupId>
    <artifactId>agent-guard-spec</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

当前为本地 SNAPSHOT，先安装本地制品或发布到团队仓库。新框架的生产代码不要直接引用 `DefaultGuardEngine`、`AgtGuardProvider`、`StructuredPolicy`。

业务应用再按需依赖安全 SDK，例如 `agent-guard-agt`。SDK 传递引入具体引擎和策略实现：

```text
业务应用
├── 新 Agent 框架 / 护栏适配模块
│   └── agent-guard-spec
└── agent-guard-agt
    ├── agent-guard-core → agent-guard-spec
    └── agt-core
```

### 2.2 三方责任

| 组件 | 负责 | 不应承担 |
| --- | --- | --- |
| Agent 框架 | 生命周期检查点、原生对象转换、执行改写与终止 | 写死某个 SDK 的业务规则 |
| GuardEngine | 阶段分发、Guard 编排、决议与故障处理 | 替框架执行模型、工具或业务事务 |
| SDK / Provider | 具体内容检测、权限与参数规则 | 猜测宿主身份、擅自获得工具执行权 |

## 3. 六阶段应放在什么位置

![新框架的护栏生命周期](assets/framework-integration/framework-lifecycle.svg)

[Mermaid 图表源码](assets/framework-integration/framework-lifecycle.mmd)。

| 阶段 | 放置位置 | 输入类型 | 放错位置的后果 |
| --- | --- | --- | --- |
| AGENT_INPUT | 请求解析为用户消息后，RAG、模板处理与模型执行前 | AgentInput | 先做了检索或执行，输入阻断已无法避免前置操作 |
| MODEL_INPUT | 完整模型请求准备完成后，每一次实际模型调用前 | ModelRequest | 漏检系统消息、历史、RAG 上下文、工具定义或重试 |
| MODEL_OUTPUT | 每次完整模型响应返回后，消费回答或调度工具前 | ModelResponse | 未经检查的工具调用可能已经执行 |
| TOOL_INPUT | 单次工具实际执行前，在有效参数绑定到方法或 RPC 之前 | ToolCall | 工具副作用已经发生，授权检查太晚 |
| TOOL_OUTPUT | 取得正常结果或框架生成的可见错误结果后，进入下一轮历史前 | ToolResult | 敏感结果已经发送给模型或写入后续流程 |
| AGENT_OUTPUT | 业务输出组装、解析完成后，对外序列化或发送前 | AgentOutput | 只检查原始模型文本，漏掉业务最终加工内容 |

`AGENT_INPUT` 与 `MODEL_INPUT` 不可互相替代：前者检查用户入口，后者检查每次真正发送给模型的完整材料。

`MODEL_OUTPUT` 与 `AGENT_OUTPUT` 也不同：前者包括中间工具调用，后者面向最终业务返回。

## 4. 构建生命周期：配置与 SPI

### 4.1 建议增加的配置入口

下列是新框架 API 的设计示意，不是已经发布到 spec 的 Builder：

```text
AgentBuilder.guardEngine(GuardEngine engine)
AgentBuilder.guardAutoDiscovery(boolean enabled)
AgentBuilder.guardContextFactory(每次请求的上下文工厂)
```

| 配置 | 建议默认值 | 行为 |
| --- | --- | --- |
| guardEngine | null | 非空时直接使用，不扫描 SDK 工厂 |
| guardAutoDiscovery | false | 显式开启且无手动引擎时才执行 SPI 发现 |
| guardContextFactory | 框架提供的可信上下文构造逻辑 | 每次调用产生新的根上下文 |

已有 Agent 配置对象应不可变或保证安全发布。引擎须线程安全；请求状态不能保存在共享引擎字段中。

### 4.2 在 build 时发现引擎

参考源码 `FrameworkGuardAdapter.discover()` 的签名：

```java
GuardEngine engine = FrameworkGuardAdapter.discover(
        explicitEngine,
        autoDiscovery,
        Thread.currentThread().getContextClassLoader());

FrameworkGuardAdapter adapter = new FrameworkGuardAdapter(engine);
```

以上变量由框架 Builder 提供。该方法在构建阶段执行一次，不放到每次 `run()` 或每个检查点中。

发现规则：

1. 默认关闭自动发现；显式引擎优先，或未开启发现时不执行 SPI。
2. 通过 `ServiceLoader<GuardEngineFactory>` 实例化所有工厂。
3. 没有工厂，返回 null；多个工厂，明确报冲突。
4. 只有一个工厂时调用 `create()`，禁止返回 null。
5. 注册错误、初始化失败和链接错误使构建失败，保留原因。

工厂须提供轻量的 public 无参构造器；先发现和检查唯一性，再调用 `create()`。工厂不是每个请求的上下文工厂。

参考实现使用调用方提供的 ClassLoader，空值时使用适配类的 ClassLoader。插件化框架应遵循自身类加载规则，必要时明确实现上下文加载器到框架加载器的回退，并避免重复发现同一工厂。

框架只扫描 `GuardEngineFactory`。是否扫描 `GuardProvider`、如何组合规则，是 SDK 工厂和引擎的内部职责。

没有 SDK 时不启用护栏是可选接入契约，不等于安全检查通过。若某业务必须启用护栏，应在构建阶段增加显式校验：

```java
if (engine == null && requireGuardEngine) {
    throw new IllegalStateException("This Agent requires a GuardEngine");
}
```

`requireGuardEngine` 是框架可选配置，不是 spec 现有字段。

## 5. 请求生命周期：身份与隔离

在请求开始时，根据认证入口取得身份，再创建请求会话：

```java
FrameworkGuardAdapter.RequestSession session =
        FrameworkGuardAdapter.beginRequest(() -> GuardContext.builder()
                .traceId(requestTraceId)
                .agentId(configuredAgentId)
                .tenantId(authenticatedTenantId)
                .userId(authenticatedPrincipalId)
                .attributes(Map.of(
                        StandardAttributes.PERMISSIONS, authenticatedPermissions))
                .build());
```

变量含义：`requestTraceId` 来自请求追踪，`configuredAgentId` 来自宿主配置，其他身份与权限来自认证、授权结果。它们不能来自用户自由输入或模型生成的 JSON 参数。

当前 AGT Provider 将 `userId` 映射为 `principalId`，服务账号也可以作为操作主体。spec 允许某些身份为空，AGT 当前要求 `agentId`、`tenantId`、主体身份非空且合法；不能因业务缺少字段随意伪造身份，应明确业务映射或调整接入策略。

上下文使用规则：

- 上下文工厂每次返回新的根对象；不要跨请求缓存 `RequestSession` 或根 state。
- 阶段派生使用 `root.withStage(stage).withIteration(iteration)`，保持本请求 state 共享。
- `root.toBuilder().build()` 会创建新 state，不适合作为阶段派生替代。
- state 的并发 Map 仅保证单个操作的并发安全，多步更新应使用 `compute`、原子对象或业务同步。
- 请求中的工具应携带产生它的 `ModelTurn`，不读取一个可能被其他并发分支改写的“全局最新轮次”。

## 6. Agent 输入生命周期：检查后再进入处理管线

```java
AgentInput incoming = AgentInput.of(userText);
AgentInput effectiveInput = adapter.beforeAgent(incoming, session);

// 后续 RAG、Prompt 模板、用户消息写入均使用 effectiveInput。
ModelRequest initialRequest = pipeline.prepareRequest(effectiveInput);
```

`pipeline` 是你的框架现有处理管线。若有模板变量，构建 `AgentInput` 时也要填入 `variables`，并将有效变量应用回模板。只转换 `userText` 会漏掉变量渠道。

框架若只支持文本，应在进入此入口前明确拒绝不支持的内容类型，而不是偷偷丢掉图片、附件等只保留文字。

## 7. 模型生命周期：每次请求与完整响应都要检查

### 7.1 模型请求检查位置

先合并系统消息、历史、RAG、工具与模型参数，再调用模型包装入口：

```java
FrameworkGuardAdapter.ModelTurn turn = adapter.callModel(
        initialRequest,
        session,
        transport::send);
```

`transport.send(ModelRequest)` 由框架实现，负责将传入的 spec 对象转为原生模型请求并发送，然后将完整响应转回 `ModelResponse`。它必须发送方法实际收到的有效请求，不能闭包捕获并发送旧的 `initialRequest`。

接入骨架内部的关键顺序是：

```text
分配 iteration
    → MODEL_INPUT 检查
    → 取得有效 ModelRequest
    → 从有效请求提取本轮允许的工具集合
    → transport.apply(有效请求)
    → MODEL_OUTPUT 检查
    → 返回有效请求、有效响应、允许工具集合和 iteration
```

完整请求至少覆盖：

- 所有实际发送的消息，包括 system、历史和检索上下文。
- 工具名称、描述、JSON Schema。
- 模型名称及能表示的参数；框架专属参数在转换时要有明确策略。
- 消息中的工具调用和工具调用关联 ID。

`newMessagesFrom` 只是新增消息的索引，不能当成历史已经安全的证明。基础实现可设置为 0；除非 Guard 有明确安全的增量检测机制，不根据该字段跳过历史检查。

### 7.2 模型输出检查位置

`MODEL_OUTPUT` 必须早于这些动作：

- 把模型工具调用交给执行器。
- 把响应写入持久记忆或作为后续模型的历史。
- 将模型输出交给业务解析器并返回用户。

后续必须使用 `turn.response()`，不得继续读取未检查的原始响应。Guard 删除的工具调用不能执行；改写后的参数必须继续传递到工具输入检查。

### 7.3 重试和默认参数合并

工具循环、输出验证失败后的重试、框架主动发起的新模型请求都应走 `callModel()`。

如果 HTTP 客户端内部自行重试，包装入口只能覆盖逻辑调用，看不到每次底层网络尝试。要声称每次实际尝试都覆盖，必须关闭隐藏重试，或把护栏包装放到重试器内部的实际发送边界。

`MODEL_INPUT` 后不能再次合并原始默认工具或消息，否则可能恢复已经过滤的工具或敏感文本。必要时把所有默认合并移到检查之前；无法保证时明确失败。

## 8. 工具生命周期：执行前授权，结果进入模型前检查

### 8.1 只能执行有效模型响应中的调用

```java
List<ToolResult> effectiveResults = new ArrayList<>();
for (ToolCall call : turn.response().message().toolCalls()) {
    ToolResult result = adapter.callTool(
            call,
            turn,
            session,
            toolDispatcher::execute);
    effectiveResults.add(result);
}
```

`toolDispatcher.execute(ToolCall)` 是框架现有的实际工具调度器。JSON 解析、方法参数绑定、RPC 发出等应根据传入的有效 ToolCall 执行。参考代码串行执行，未承诺一批工具的原子性。

`callTool()` 依次执行：

1. 校验工具在本轮有效请求的允许集合中，防止模型直接点名已过滤工具。
2. `TOOL_INPUT` 检查，取得有效工具参数。
3. 校验改写未更换工具名或调用 ID。
4. 调用真实执行器。
5. 校验结果与原调用关联一致。
6. `TOOL_OUTPUT` 检查，取得有效结果。
7. 校验改写未更换关联身份或错误标志，返回有效结果。

工具权限由 Provider 判断，允许工具集合不能替代业务授权。业务执行器自身的租户隔离和数据库权限仍应保留。

### 8.2 工具错误如何处理

参考代码让工具执行异常直接向上传播，此时请求终止，不存在返回模型的工具结果。

如果框架会把可恢复的业务异常转为模型可见的错误消息，应在 `toolDispatcher.execute()` 内针对明确的业务异常构造 `ToolResult.error(...)`，这样仍会经过 `TOOL_OUTPUT`。

不要捕获所有 `Exception` 后将其转换为“工具失败，请继续”：GuardBlockedException、GuardExecutionException、取消和中断不能被这种错误处理吞掉。不要把堆栈、连接字符串或原始异常细节直接作为工具错误内容发送给模型。

### 8.3 使用有效结果继续模型循环

```java
ModelRequest nextRequest = pipeline.continueConversation(
        turn.request(),
        turn.response(),
        effectiveResults);

FrameworkGuardAdapter.ModelTurn nextTurn = adapter.callModel(
        nextRequest,
        session,
        transport::send);
```

这里必须使用 `turn.request()`：它是本轮 MODEL_INPUT 的有效请求。如果重新从旧请求组装历史，前一轮被脱敏的输入或过滤的工具可能再次出现。

`continueConversation()` 还要完整保留工具调用与工具结果的对应关系，不能把未检查的原始工具结果写入记忆、缓存或后续历史。

## 9. Agent 最终输出生命周期

没有后续工具调用时，由框架完成结构化解析或业务结果组装，再做最后检查：

```java
AgentOutput candidate = outputCodec.toAgentOutput(turn.response());
AgentOutput effectiveOutput = adapter.afterAgent(candidate, session);
BusinessResult result = outputCodec.toBusinessResult(effectiveOutput);
return result;
```

`outputCodec` 和 `BusinessResult` 是你自己的框架类型，以上为接入位置示意。它们不在 spec 中。

对纯文本，使用 `effectiveOutput.text()` 返回。对结构化对象，需要验证改写结果仍符合原返回类型及 Schema；不能把 POJO 偷偷替换为 String。

转换后的对外内容必须与检查结果一致。若最终序列化器还会添加新的业务字段，也应将这些内容纳入检查表示，避免检查后又加入未检查的信息。

调试轨迹、内部异常和检索元数据通常不属于业务输出，不要简单地把整个框架返回包装器序列化给用户。

## 10. 在你的 Agent Loop 中组合这些检查点

下面是框架侧整体伪代码。`pipeline`、`transport`、`toolDispatcher`、`outputCodec` 和 `BusinessResult` 由真实框架提供；护栏适配器本身可直接查看附带的完整 Java 文件。

```java
RequestSession session = beginRequest(contextFactory);
AgentInput input = adapter.beforeAgent(incomingInput, session);
ModelRequest request = pipeline.prepareRequest(input);

for (int round = 0; round < maxModelRounds; round++) {
    ModelTurn turn = adapter.callModel(request, session, transport::send);
    if (!turn.response().hasToolCalls()) {
        AgentOutput output = outputCodec.toAgentOutput(turn.response());
        AgentOutput effective = adapter.afterAgent(output, session);
        return outputCodec.toBusinessResult(effective);
    }

    List<ToolResult> results = new ArrayList<>();
    for (ToolCall call : turn.response().message().toolCalls()) {
        results.add(adapter.callTool(call, turn, session, toolDispatcher::execute));
    }
    request = pipeline.continueConversation(turn.request(), turn.response(), results);
}
throw new IllegalStateException("Agent exceeded model round limit");
```

轮次上限是框架的运行保护，不等于 AGT 已经执行预算治理。若循环耗尽，应明确结束；若要返回业务提示，也应对该提示走最终输出检查，而不是返回最后一个未经检查的中间对象。

## 11. 转换器必须实现的契约

真实框架通常有自己的消息、工具和结果类型。参考适配器直接使用 spec 作为内部表示，是为了突出执行顺序；接入真实框架时仍需实现双向转换。

| 原生类型 | spec 类型 | 应保留的信息 |
| --- | --- | --- |
| 用户输入 | AgentInput | 用户消息、模板变量及相关元数据 |
| 完整模型请求 | ModelRequest | 消息角色和顺序、内容片段、工具定义、模型与参数 |
| 完整模型响应 | ModelResponse | 回答、工具调用、模型、结束原因、usage、元数据 |
| 工具调用 | ToolCall | callId、toolName、JSON arguments、元数据 |
| 工具结果 | ToolResult | 调用身份、content、error 标志及元数据 |
| 最终业务返回 | AgentOutput | text、structured 和元数据 |

转换失败、未知消息类型、非法工具 JSON、无法应用的改写属于适配失败，不能被 SDK 的 FAIL_OPEN 策略绕过。参考适配器对工具身份改写等适配失败抛 `GuardExecutionException`；原生转换器也应明确失败并保留 cause。

若原生结果有 provider 专有字段，未发生改写时可以保留原对象；发生改写时，要只替换明确支持的部分并保留其余字段。不要静默丢弃无法理解的数据，也不要根据 `toString()` 拼出检测表示。

## 12. 决议与异常如何落实

| 结果 | 框架动作 |
| --- | --- |
| PASS | 继续使用当前有效 payload |
| MODIFY | 将新 payload 转回原生对象，真正替换后续输入 |
| OBSERVE | 记录观察发现，继续；使用 Engine 返回的有效结果 |
| BLOCK | 停止当前流程，调用 payloadOrThrow 落实终止 |
| REJECT | 实现阶段对应的 reply 替代语义；不支持时按阻断处理 |
| 检测故障，FAIL_CLOSED | 传播 GuardExecutionException，不重试为正常业务成功 |
| 检测故障，FAIL_OPEN | 按 Engine 的返回结果继续，保留故障记录 |

参考适配器统一使用 `engine.check(...).payloadOrThrow()`，所以所有阶段的 REJECT 都终止。它没有实现 LangChain4j 工具阶段的 reply 替代能力，这是本教学骨架明确的边界。

不要仅调用 `outcome.payload()`：BLOCK 时它也可能含有原对象，直接使用会绕过阻断。不要在框架外层宽泛捕获异常后继续使用旧请求、旧响应或原工具结果。

取消、中断与致命 Error 应按框架原有语义传播，不能归类为普通检测失败后放行。

## 13. 异步、并行、流式与资源生命周期

### 13.1 异步

把 `RequestSession`、`ModelTurn` 显式捕获到任务，保证检查结果完成之后才启动下一步操作。不要把检查与工具执行同时提交，让二者竞速。

本参考代码为同步 API，不应直接据此宣称框架已支持非阻塞检查。远程 Guard 需要有界执行器、SDK 自身的连接和读取超时；框架 Future 超时也不等于实际网络调用被取消。

### 13.2 并行工具

每个工具任务带着产生它的有效 ModelTurn 与同一请求的上下文。一个工具被阻断只保证它不执行，不能回滚或自动取消其他已经启动的工具。

若业务要求一批工具全部允许后才能启动，需要增加整批预检，并配合事务、幂等或补偿设计。不要在预检后又重复调用带副作用的 Guard 而不定义其执行契约。

### 13.3 流式与媒体

本项目 SDK 与 LangChain4j 已新增可选 StreamingGuardEngine / GuardStream 契约，实际 TokenStream 接入见 [流式使用指南](streaming-usage.md)。本文附带的教学骨架仍为同步非流式，不能直接据此宣称已支持流式。

当前参考入口等待完整 ModelResponse，仅覆盖非流式。如果框架支持流式，启用护栏后必须明确选择缓存后检查或经过设计的增量策略；无法支持时在 build 阶段拒绝组合。

不能先将 token 发给用户，再在流结束时声称最终输出检查能避免泄漏。多模态也应有明确表示与检测能力，不支持时明确失败。

### 13.4 资源管理

GuardEngineFactory 接口未定义关闭方法。SDK 创建的客户端、连接池、审批线程池等资源由 SDK 或宿主按明确约定管理，框架不能假设任意引擎可强转 AutoCloseable 自动关闭。

## 14. 编译和运行附带骨架

先找到已安装的 spec JAR。以下以 Maven 默认本地仓库为例，自定义仓库时调整 `SPEC_JAR`：

```shell
SPEC_JAR="$HOME/.m2/repository/org/agentguard/agent-guard-spec/1.0.0-SNAPSHOT/agent-guard-spec-1.0.0-SNAPSHOT.jar"
mkdir -p target/framework-guide
javac --release 17 -cp "$SPEC_JAR" -d target/framework-guide \
    docs/examples/framework-integration/FrameworkGuardAdapter.java \
    docs/examples/framework-integration/FrameworkGuardAdapterSmoke.java
java -cp "target/framework-guide:$SPEC_JAR" guide.FrameworkGuardAdapterSmoke
```

在示例项目根目录执行；上面 classpath 分隔符适用于 macOS/Linux，Windows 使用分号。需要 JDK 17+，无需 AGT、core、LangChain4j 或外部模型凭证。

预期输出：

```text
PASS: six stages, effective modifications, tool blocking and per-turn tool filtering
```

这个验证使用自定义测试引擎，只确认接入骨架的执行契约，不代表 AGT SDK 装配、真实模型、并发负载或所有异常场景已经验证。AGT 与 LangChain4j 的实际组合可继续使用本项目已有 Demo 和测试。

## 15. 真实框架接入验收清单

| 场景 | 验收证据 |
| --- | --- |
| 无 SDK / 自动发现关闭 | 行为符合明确配置，不误报安全能力启用 |
| 有效 SDK | 真实 JAR 的 SPI 描述文件能装配，工厂只在构建阶段创建 |
| 显式引擎 | SDK 工厂不会被额外创建 |
| 多工厂 / 损坏注册 / 配置缺失 | 构建明确失败并保留原因 |
| 输入阻断 | 模型与后续检索等操作计数为 0 |
| 输入改写 | 实际模型 HTTP 请求或执行器收到新值 |
| 模型输入过滤工具 | 实际请求不含该工具，模型直接点名也无法执行 |
| 模型输出改写 | 删除的工具调用不执行，后续只使用有效响应 |
| 工具参数改写 | 业务方法收到新参数，工具身份保持一致 |
| 工具权限拒绝 | 工具副作用为 0，异常阶段为 TOOL_INPUT |
| 工具结果改写 | 下一轮模型请求、后续历史中没有原始敏感结果 |
| 最终输出改写 | 调用方收到新值，原业务类型保持正确 |
| 多轮 / 重试 | 每次模型尝试都经过输入和输出检查 |
| 并发请求 | 身份、state、轮次和允许工具集合不串用 |
| 异常与取消 | 阻断、检测故障、中断和取消不会被普通业务错误处理吞掉 |
| 流式 / 未知媒体 / 无法应用改写 | 明确支持或明确拒绝，不静默漏检 |

## 16. 对照现有 LangChain4j 实现定位

在本地 LangChain4j 仓库按以下类名检索即可：

| 新框架要实现的能力 | 已有参考 |
| --- | --- |
| 构建时 SPI 装配 | AgentGuardAutoConfiguration.configure |
| 请求上下文绑定 | AgentGuardBridge.attach、AgentGuardSession |
| 原生对象转换 | AgentGuardBridge.toModelRequest / modifyChatRequest 等 |
| 模型调用前后包裹 | GuardedChatModel.chat / chatAsync |
| 单次工具执行前后包裹 | ToolService 的同步、异步执行路径 |
| Agent 输入和最终输出 | DefaultAiServices 的请求与返回路径 |

具体源码路径及已有调用时序见[调用链文档](langchain4j-agt-call-chain.md)。框架接入时优先复用自身的模型装饰器、工具执行拦截器和请求上下文扩展点；只有公开扩展点不足以覆盖真实边界时，才修改内部执行管线。
