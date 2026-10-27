# Agent Guard 的边界与验收契约

## 目标与范围

为不同 Java Agent 框架提供同一套安全 SDK 接入契约。规范层仅依赖 JDK 8；保留现有六阶段接口。
本次修复核心编排与 LangChain4j 原型接入，覆盖非流式同步、异步、工具循环和输出 Guardrail 的模型重试。
TokenStream 流式缓存与窗口检测现已按 [流式契约](streaming-design.md) 显式接入。Spring AI、媒体、远程 SDK 与批量工具原子预检仍不宣称支持。

## 分层与选型

| 层 | 职责与依赖 |
| --- | --- |
| spec | 中立数据模型、Guard SPI、Engine 门面、策略与事件；仅 JDK 8 |
| core | spec 的编排参考实现；不执行模型或工具 |
| SDK Provider | 依赖 spec，将检测结论转换为 GuardDecision |
| 框架 Adapter | 依赖 spec，在真实生命周期边界调用 Engine 并应用结果 |
| 应用 | 组装 Engine、SDK 和 Adapter，提供可信身份与执行器 |

沿用现有 Maven/Java 8，避免为基础 SPI 引入框架依赖。现有 LangChain4j Guardrail 不能覆盖逐轮模型和工具边界，因此保留六阶段接口。当前 LangChain4j 仍为 fork 内接入，但生产依赖改为 spec，core 仅用于测试；后续可按框架公开扩展点抽成适配模块，不在这次修复中重做框架基础设施。

参考：
- https://docs.langchain4j.dev/tutorials/guardrails/
- https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html

## 生命周期

- AGENT_INPUT：用户消息准备后、RAG 与模型处理前。
- MODEL_INPUT：每次实际模型调用前，检测完整请求；newMessagesFrom 默认 0，不能仅凭该索引跳过历史安全检查。
- MODEL_OUTPUT：每次完整模型响应后、工具执行前；同时检查文字与工具调用。
- TOOL_INPUT：实际工具调用前，使用独立于模型参数的可信身份。
- TOOL_OUTPUT：正常或转为可见错误文本的工具结果进入下一步之前；不能撤销已发生的工具副作用。
- AGENT_OUTPUT：最终业务内容返回前。Result 等框架封装中的调试、来源、轨迹字段不属于业务内容；不应直接向终端用户公开整个框架封装。

每个请求创建独立根 GuardContext，阶段和轮次通过 withStage/withIteration 派生，显式传递同一个 state。state 的并发 Map 不保证多步读改写原子性，Guard 应使用 compute/原子对象或业务同步。

## 决议与异常

- PASS：保留输入。
- OBSERVE：记录发现，保留输入。
- MODIFY：后续 Guard 与实际操作使用修改结果；每次改写校验阶段类型。
- REJECT：不再使用当前原始结果，以 reply 代替当前步骤；Adapter 不支持时按 BLOCK 终止。
- BLOCK：停止后续流程，Adapter 通过 payloadOrThrow 抛出 GuardBlockedException。

SDK 发现违规返回 BLOCK；SDK 网络、协议与服务异常为检测故障。Engine 将故障记录为 error，decidedAction 为 null，不伪造检测决议；FAIL_CLOSED 的 payloadOrThrow 抛 GuardExecutionException，并保留 cause。FAIL_OPEN 仅作用于检测故障，不能降级显式 BLOCK。兼容 Provider 显式抛出的 GuardBlockedException；OBSERVE 模式仍可旁路该拒绝。

策略解析之前无可信动态策略，先获取 Guard 的默认策略；默认策略获取失败使用全局 ENFORCE/FAIL_CLOSED，动态策略解析失败使用已获取的默认策略。supports 在策略解析后执行，其故障使用已解析策略。禁用时不调用 supports 或 check。
取消异常、已中断线程的异常以及 Error 直接传播，不以 FAIL_OPEN 放行。审计监听器的普通 RuntimeException 不影响主流程，致命 Error 不吞掉。没有配置 Guard 的阶段仍发送空记录事件，避免把“未检查”误当成“检查通过”。

适配失败不是检测结论：无法转换或无法应用的改写必须以 GuardExecutionException 终止，不受 Provider 的 FAIL_OPEN 策略影响。

## 安全约束

Guard id 全局非空、非空白且唯一，stage 非空；check 入参非空并匹配阶段类型。
阶段内按 order、id 顺序执行。参数改写应在权限检查之前；同一阶段后续 Guard 不得让已完成的授权失效。Engine 不自动重排业务规则。

身份只从可信应用注入，不能相信 prompt 或模型生成的 tenantId。SDK 远程调用应配置连接、读取和总超时；本项目不通过 Future 超时伪装为已取消远程请求。
并行工具各自检查，只保证被拒绝的工具不执行，不保证其他已启动工具取消或回滚。需要整批拒绝时应另外实现整批预检与业务事务/幂等设计。

## LangChain4j 当前能力

- 支持同步与 CompletableFuture 的非流式文本、工具参数检查和改写、工具结果替换与模型工具调用改写。
- 模型调用装饰器覆盖首轮、循环和输出 Guardrail 的重试。每次模型调用分配递增轮次；工具沿用当前轮次。
- 支持 MODEL_INPUT 的文本消息修改和工具集合过滤；工具执行同时校验本轮允许集合，模型直接点名被过滤工具也会被阻断；暂不支持模型/参数/工具 Schema 改写及带 Provider 专有参数的工具过滤，明确报错。
- 模型默认工具若可能在合并参数时恢复，移除全部默认工具明确失败。
- 非工具阶段 REJECT 按 BLOCK 处理；TOOL_INPUT/TOOL_OUTPUT 支持 reply 替代，不传递原始结果。
- 工具名称与调用 ID 不允许改写；跨工具权限域的路由应由应用控制。
- 结构化输出以 JSON 提供检测表示；改写须返回原类型 structured 对象，不能将 POJO 或 Result<POJO> 变成字符串。
- 文本之外的媒体/未知消息暂不支持，转换时明确报错，不能只抽取文本后静默放行。
- TokenStream 通过 guardStreaming 显式选择 STRICT 或 WINDOW；默认未启用、引擎无能力或配置不兼容时明确拒绝。Flow.Publisher 的独立状态机组合暂不支持。
- guardContextFactory：每次请求创建根上下文，默认读取可信 InvocationParameters 的 tenantId/userId；应用可覆盖。
- guardExecutor：异步模型检测执行器，由调用方管理。默认 commonPool 仅适合轻量本地检测，远程检测应提供有界专用执行器；Agent/工具同步 Guard 的耗时仍计入相应业务调用，不能宣称所有扩展点完全非阻塞。

## 验收

- [x] 注册错误和阶段 payload 错误在执行前被拒绝。
- [x] 检测故障、显式拒绝、观察模式、取消与致命错误符合上述异常契约。
- [x] 空阶段产生审计，跨阶段 state 共享，不同请求 state 隔离。
- [x] MODEL_INPUT 改写实际传给模型，工具过滤能清空集合，被过滤工具即使被模型直接调用也不会执行。
- [x] MODEL_OUTPUT 改写可移除危险工具调用，原始调用不会执行。
- [x] TOOL_INPUT 阻断后工具无副作用；TOOL_OUTPUT REJECT 不泄漏原结果。
- [x] 多轮和异步检测不漏执行；输出 Guardrail 重试经过同样检测。
- [x] POJO 与 Result<POJO> 改写不改变返回类型；不支持的改写明确失败。
- [x] 未启用模式或能力不兼容的流式组合在构建时被拒绝，多模态在转换时被拒绝。

## 本轮验证记录

- Agent Guard：`mvn -o test`，19 个测试通过，包含新增 12 个契约测试。
- LangChain4j：通过项目 Maven Wrapper 执行 AiServicesAgentGuardTest、AgentGuardContractTest、ToolServiceTest、AiServicesTest、AiServicesAsyncTest、OutputGuardrailChatRequestTest，共 114 个测试通过，其中新增 13 个接入契约测试。
- 历史验证中的流式拒绝不代表流式实现；新增 TokenStream 能力及验证单独记录在流式契约中。媒体仍明确拒绝。
- 未运行整个 LangChain4j 多模块工程的所有测试，也未运行 Sonar。
- LangChain4j 本轮 8 个 Java 文件通过仓库 Spotless 格式化检查；两个工作区通过 `git diff --check`。


## SDK 自动装配契约

本次在中立规范中新增 GuardEngineFactory，SDK 在服务描述文件注册实现；采用参考引擎的 SDK 可以直接注册 core 的 DefaultGuardEngineFactory，再发布 GuardProvider。core 不自带工厂注册文件，单独引入 core 不会自动启用护栏。默认工厂在没有检测规则时初始化失败，不能把 SDK 安装错误表现为安全检查已启用。

框架继续只依赖 spec，SDK 传递引入 core 或使用自己的引擎实现。采用 Java 8 ServiceLoader，无新增框架依赖；沿用 LangChain4j 的 ServiceHelper 处理线程上下文类加载器及回退机制。服务发现、无参构造与描述文件格式遵循 [JDK SPI 文档](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)。

- 服务 build 时发现工厂，后续请求复用引擎；不做全局静态缓存。
- 默认自动发现；没有工厂时维持原有行为。
- 显式 guardEngine(engine) 优先，不扫描、不构造自动工厂。
- guardAutoDiscovery(false) 关闭扫描，不关闭显式配置的引擎。
- 多个工厂报冲突，不按优先级或 classpath 顺序选一个；工厂构造应轻量。
- 注册损坏、缺少类、工厂返回 null 或初始化异常均使 build 明确失败，保留 cause。
- 自动启用后仍执行流式兼容性校验，不能因自动装配绕过原有保护。
- 每个构建上下文只初始化一次已发现的引擎；线程池和 SDK 客户端生命周期由 SDK/应用管理。

验收需覆盖无 SDK、真实服务描述文件自动生效、显式覆盖、关闭发现、重复工厂、损坏注册、初始化失败、null 引擎、空规则、构建时初始化和流式拒绝。测试描述文件使用临时 JAR 和隔离类加载器，不能全局污染其他测试。


### 自动装配验证记录

- 核心原有 19 个测试通过；新增默认工厂的有效/空规则/损坏 Provider 场景在 LangChain4j 自动装配测试中覆盖。
- LangChain4j 的 AgentGuardAutoConfigurationTest 新增 16 个测试通过；连同六阶段及相关模型、工具、异步、重试回归共 130 个测试通过。
- 运行时 Maven 依赖树仅包含 org.agentguard:agent-guard-spec，不含 core 或具体 SDK；core 仍仅用于 LangChain4j 测试。
- 从自动装配指南提取 SDK 示例，以 --release 8 编译并打包真实 JAR；仅依赖 spec 编译的外部调用方通过 ServiceLoader 发现工厂并成功触发 SDK 拒绝。
- 不声称验证了远程 SDK、Spring AI 或实际 JDK 8 运行时。
