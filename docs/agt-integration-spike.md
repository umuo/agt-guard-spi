# AGT 接入 Spike

此文保留接入前对原始 Java 17 源码的调研记录。复制后的模块已完成 Java 8 兼容改造，当前构建与能力边界以 [实现说明](agt-implementation.md) 为准。

日期：2026-10-23。评估对象：当前 Agent Guard 工作区、AGT 本地源码提交 `f672c1c`（`0.6.0-SNAPSHOT`）。

## 结论

**适合接入，建议将 AGT 作为可选的治理实现，通过独立 `agent-guard-agt` Provider 模块接入。**
保留 `agent-guard-spec` 的框架中立性及 Java 8 基线；框架负责在真实 hook 调用 GuardEngine，AGT 负责具体规则。
AGT Provider 模块及使用它的应用要求 Java 17+。不把 AGT/Jackson 直接加进 spec 或参考 core。

当前六阶段契约足以承载 AGT 的无状态内容与工具策略；**不足以等价承载 AGT 完整运行时治理**。
完整接入涉及开始/结束、执行失败、取消、预算预占与实际执行关联、数据访问和流式交付，不能仅增加六个 Guard 类后宣称完成。

本次交付是源码调研与隔离的可运行原型，没有修改生产模块、AGT 仓库或框架 hook。

## 需求与验收范围

- 验证“框架调用中立 SPI，AGT 实现规则”这一依赖方向成立。
- 验证放行、拒绝、改写、租户权限绑定、严格参数解析，以及改写后再次授权校验。
- 确认审批和调用预算是否能由现有检查方法直接继承。
- 明确接入收益、兼容性、实施顺序和不可承诺的能力。

原型只实现纯文本 AGENT_INPUT 与 JSON 参数 TOOL_INPUT；其余四阶段为源码映射分析，尚未实现和运行验证。
未验证真实 LLM、Spring AI、六阶段框架端到端链路、性能或生产负载。

## 推荐分层

```text
LangChain4j hooks ─┐
Spring AI hooks ──┼──> agent-guard-spec <── agent-guard-core
自定义框架 hooks ─┘            ^                   ^
                              └── agent-guard-agt ─┘
                                         |
                                         v
                                      agt-core
```

这里的 `agent-guard-core` 是现有参考编排引擎，AGT 的 `agt-core` 是策略/运行时实现，两者职责不同。
`agent-guard-agt` 是建议的新增模块名，当前未创建正式 Maven 模块。

框架只依赖 spec；Provider 依赖 spec、参考 core 和 agt-core。采用参考引擎时，在 `GuardProvider` 发布阶段规则，
通过一个 `GuardEngineFactory` 组装策略、Provider 和审计。应用选择 AGT SDK 后，已适配的框架即可复用它。
如果已有其他 SDK 工厂，必须在同一个组合工厂中组装，或显式配置组合引擎；当前契约不允许多个自动工厂随机竞争。

另一方向也成立：AGT 的框架适配器调用 GuardEngine，再挂其他安全 SDK。但这需要改动 AGT 适配器；
针对当前“框架只接一次通用 SPI”的目标，优先把 AGT 做成 Provider，避免继续为每种 SDK 重写框架集成。

## 六阶段匹配

| Agent Guard | AGT | 转换与限制 |
| --- | --- | --- |
| AGENT_INPUT | INPUT | 投影消息、模板变量；原型仅投影 text |
| MODEL_INPUT | PRE_MODEL_CALL | 投影全部消息、工具及所需参数，不只检查最新消息 |
| MODEL_OUTPUT | POST_MODEL_CALL | 同时保留文本与 tool calls，不能只检查 text |
| TOOL_INPUT | PRE_TOOL_CALL | arguments JSON 解析为 Map；工具名独立传入；可信身份从上下文取得 |
| TOOL_OUTPUT | POST_TOOL_CALL | 投影结果、错误标志及结构化表示，恢复时保持类型 |
| AGENT_OUTPUT | OUTPUT | 检查最终业务内容；结构化结果不能静默变成字符串 |

额外的 `AGENT_STARTUP/AGENT_SHUTDOWN` 没有现成映射；正常输出 hook 不能代替异常、取消时的关闭。
`PRE_DATA_ACCESS/POST_DATA_ACCESS` 也没有对应阶段，不能把 TOOL_INPUT 当成所有 RAG/Memory 访问的授权。

关键源码：

- Agent Guard：`GuardEngine`、`GuardProvider`、`GuardEngineFactory`、`GuardContext`、`DefaultGuardEngine`。
- AGT：`PolicyEvaluator`、`PolicyRequest`、`StructuredPolicy`、`TrustedContext`、`AgentControl`、`HostSession`。
- AGT 已有适配示范：`GovernedChatModel` 和 `GovernedToolExecutor` 调用 `operationPrepared/operationAsyncPrepared`，不只是前后各 evaluate 一次。

## 接入后有哪些收益

| 收益 | 具体作用 | 首期策略 Provider 是否可得 |
| --- | --- | --- |
| 统一 SDK 接入 | LangChain4j、后续 Spring AI 的 hook 复用同一个 AGT Provider | 可以；各框架 hook 仍需分别实现 |
| 工具默认拒绝 | 未配置工具、权限不足、未知参数、租户绑定不符时拒绝执行 | 可以 |
| 集中策略 | 复用 Java Builder / YAML 配置、规则版本和本地确定性校验 | 可以；原型只验证 Builder |
| 有界参数处理 | 复用 JSON 重复键、深度、大小及不支持值校验 | 可以；原型验证重复键 |
| 内容拒绝与脱敏 | 复用 ContentRule，改写后恢复原 payload，并复查硬约束 | 可以；原型验证文本改写及租户复查 |
| SDK 组合 | 与内容安全、PII、注入检测等 Provider 共用编排入口 | 可以；必须约束规则顺序 |
| 审批与治理审计 | 复用动作摘要、审批关联、策略版本、执行结果等 | 需要 HostSession 及生命周期接入 |
| 预算、撤销、超时 | 限制真实模型/工具操作，管理会话和异步任务 | 需要执行边界接入 |
| 数据访问与流式治理 | 在访问和交付前控制实际路径 | 需要新增对应适配，不由六阶段自动提供 |

收益可用一个场景说明：模型要求调用 `lookup(tenantId=t2)`，宿主认证的租户是 `t1`。
Provider 使用 `ParameterRule.equalsContext=tenantId` 对照可信上下文并返回 BLOCK；框架执行 `payloadOrThrow()`，工具不会启动。
未来 Spring AI 的适配器采用同一 SPI 和上下文契约时，可以复用相同规则和反例。

AGT 的 `ContentRule` 是字符串字面匹配与替换，不是语义注入检测、自动 PII 识别或通用越狱防护。
这些能力仍需要专用安全 SDK/模型；AGT 的价值主要在于确定性授权和运行时治理。

## 两种接入深度

### 第一阶段：策略 Provider，推荐先做

复用 `StructuredPolicy/PolicyEvaluator`，由六类 Guard 做投影、评估和恢复。

| AGT 结果 | Guard 处理 |
| --- | --- |
| ALLOW | PASS |
| DENY | BLOCK；内部保留 reasonCode，对外使用安全文案 |
| TRANSFORM | validateEffective 后恢复原类型并 MODIFY |
| approvalRequired / requiresApproval | 尚未接入审批时明确拒绝，不能当成普通 ALLOW |
| 参数解析、投影、内部执行错误 | 保留为检测/适配故障，按明确的故障契约处理 |

需验证支持的 interventionPoints，缺少配置应在初始化时报错。
不能直接传 Guard DTO 给 `PolicyRequest`：其 target 经 `JsonValues.freeze`，只接受受限 JSON 值。
必须定义投影协议，尤其是模型消息、工具身份、结构化输出及多模态，不依赖默认 ObjectMapper 序列化猜测。

可信身份来自应用认证入口；permissions 使用 `StandardAttributes.PERMISSIONS`。
AGT 标识符有字符集及 128 字符限制，GuardContext 的标识没有同等强制限制，适配器须校验或映射，不能从 prompt 补身份。
原型直接使用合法 sessionId；正式版本应区分一次 invocation 的治理会话 ID 与可能跨多轮复用的业务 sessionId，并生成独立 actionId。

改写 Guard 应先于最终授权 Guard。AGT 校验之后，后续 Guard 如果再改写工具参数，可能使授权失效。
需要保证最终有效参数再次校验；不能仅依赖固定 order 值假设 AGT 永远是最后一个 Provider。

首期可使用 Agent Guard 的事件监听器记录检查结果；这不等于 AGT 的执行审计，也不自带其审计故障关闭语义。
原型使用默认 ENFORCE/FAIL_CLOSED；生产组合不得让全局观察模式旁路必须执行的授权，需明确哪些规则可观察、哪些必须强制。

### 第二阶段：完整治理，需要独立设计

AGT `HostSession.evaluate()` 已支持改写复查、审批与决策审计，但不会扣减模型/工具调用次数。
调用次数的预占、提交、执行、失败和释放在 `operationPrepared()` 等执行包装方法内。
因此，给六个 Guard 各调用一次 session.evaluate，仍不能复用完整调用预算和执行结果审计。

需要框架适配器提供一次调用的开始、结束、失败、取消，以及实际模型/工具执行的关联。
现有检查接口可以继续保留，另行设计可选生命周期/执行扩展；不要把 AGT 类型写进公共 spec。
另一种选择是继续用 AGT 已有执行包装器，但它需要每个框架自己的适配成本，且应指定唯一的计数与治理边界，避免重复检查、审批和记账。

`GuardContext.state` 可以暂存每次调用的状态/会话引用，但它只是共享 Map，不会自动关闭资源或传播异步取消。
异步场景优先显式传递 detached session，并确保完成、异常、取消路径都能关闭；不要假定 ThreadLocal 会跨线程传播。
`AgentControl` 拥有线程池，应用关闭时需要 close；AuditSink 由宿主管理。

若自定义 `GuardEngineFactory` 返回 AGT 引擎，可以替换参考编排实现，但单靠替换引擎仍补不上框架未上报的执行失败、取消和数据访问事件。

## 成本与适用性判断

- Java：公共 spec/core 继续 Java 8；AGT Provider 要求 Java 17+。Java 8 业务无法直接使用当前 AGT。
- 依赖：agt-core 引入 Jackson databind/YAML 等传递依赖，本地父 POM 管理 Jackson `2.22.1`；实际宿主 BOM 冲突需要单独验证。
- 发布：当前为 `0.6.0-SNAPSHOT`，AGT 本地源码未修改；未核实公共制品仓库可用性，不能承诺直接从 Central 下载。
- 维护：AGT 已有核心、异步、数据、流式和工具适配源码及测试，但本次未重跑 AGT 全部测试；README 也未承诺生产负载验证。
- 许可证：本地 AGT 为 MIT，分发时保留对应版权及许可声明；它是独立 Java 实现，不是微软官方 Java SDK。
- 如果只想挂远程内容审核，AGT 的授权与会话运行时可能不是必要依赖；若要工具权限、租户隔离和后续治理，则复用价值明显。

## 验证结果与复现

本次执行 `mvn -o test`：Agent Guard 现有 19 个测试通过。
执行下方源码原型：16 项检查通过。runner 在临时目录分别以 `--release 8` 编译 spec/core，
以 `--release 17` 编译 AGT 当前源码及原型；实际运行环境为 JDK 21，不代表在真实 JDK 8/17 上执行过。
runner 读取已有 Jackson 缓存，不下载依赖，不使用可能过时的 AGT target/classes，也不改动 AGT 目录。

```shell
python3 docs/spikes/agt/run.py --agt-root "$AGT_ROOT"
# Maven 缓存不在 AGT_ROOT/.cache/m2 时：
python3 docs/spikes/agt/run.py --agt-root "$AGT_ROOT" --m2 "$AGT_M2"
```

要求 JDK 17+ 的 javac/java、Python 3 和本地已存在的 AGT Jackson 依赖及 BOM。
原型使用 Builder，未加载 YAML，未验证 SnakeYAML 运行路径。

检查覆盖：正常放行、内容阻断、文本改写、合法工具、伪造租户、缺权限、未知工具、重复 JSON 键、
审批未支持时拒绝、改写后越权复查、DTO 不能直接投影、HostSession 审批、evaluate 不扣预算、
operation 扣预算、超额阻断、超额时无第二次副作用。
审批测试使用本地自动批准回调，仅验证 SDK 机制，未连接人工审批系统。

源码原型：[AgtBridgeSpike.java](spikes/agt/AgtBridgeSpike.java)，执行器：[run.py](spikes/agt/run.py)。
原型没有安装 ServiceLoader 描述文件，不会因加入项目就自动启用 AGT，也未验证正式工厂发现链路。

## 后续实施建议

1. 保持当前 SPI 分层，新增 Java 17 可选 Provider 模块，先完成六阶段投影和策略接入。
2. 通过现有工厂自动装配，补全缺配置失败、可信身份、改写恢复、组合规则排序与异常分类验收。
3. 在 LangChain4j 实际 hook 上验证多轮、重试、并行工具以及阻断前无副作用。
4. Spring AI 选定版本后接同一契约，再评估是否确实需要生命周期/执行扩展来复用 AGT 全治理。

截至调研时，Spring AI 官方 2.0.1 文档的 `ToolCallingAdvisor` 提供逐轮模型调用的 protected hooks，
并支持同步和流式两组路径；这是后续适配的候选入口，不表示六阶段已经全部实现，
逐个工具授权仍需覆盖实际工具执行入口。参见 [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html)。
LangChain4j 官方 Guardrails 是 AI Services 的高层入口，不能据此推断裸模型和所有工具边界都被保护；
当前项目已接入的六阶段版本仍有自身价值。参见 [LangChain4j 官方文档](https://docs.langchain4j.dev/tutorials/guardrails/)。
