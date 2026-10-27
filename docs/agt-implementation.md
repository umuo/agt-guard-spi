# AGT 策略接入实现契约

本次在独立 worktree 中复制 AGT `f672c1c` 的 core 源码及测试，保留包名和 MIT 许可。
新增可选模块 `agent-guard-agt`，以六阶段 Provider 复用 AGT 策略；当前四个模块均以 Java 8 为源码和运行时基线。

验收：六阶段检测和改写实际返回原类型；工具身份不可改写；改写后再次校验参数及权限；
审批需求在未实现审批时明确阻断；可信身份缺失、非法 JSON、媒体及不支持的结构化载荷明确失败；
通过真实 ServiceLoader 描述文件装配，配置缺失或策略缺少六阶段检查点时初始化失败。
运行原有 Agent Guard、复制的 AGT core 及新增接入测试。

本次复用策略层，不启动 AgentControl，不承诺 AGT 的预算、会话审批、取消、执行审计、数据访问与流式治理。
这些执行语义需要框架生命周期扩展，详见 [接入调研](agt-integration-spike.md)。

Java 8 改造验收：保留构造校验、不可变集合、值比较、脱敏输出与 YAML 属性名；
真实 JDK 8 执行全部测试和 JAR 自动装配，生成的所有生产 class 均为 major 52；
JDK 17 使用同一套 Java 8 源码执行交叉验证。不能靠仅修改 target 或跳过测试来宣称兼容。

## 纯代码便捷入口

常用规则可以通过 `AgtGuards.builder()` 配置，自动创建策略、Provider 和引擎，无需 YAML。用法与配置校验见 [Fluent Builder 指南](agt-fluent-api.md)。已有的底层 API 和自动装配契约继续保留。

## 构建与使用

默认构建已包含全部四个模块；`agt` profile 保留为兼容入口，不重复声明模块。JDK 8+ 构建完整工程：

```shell
mvn -Pagt verify
# 需要供其他项目引用本地制品时：
mvn -Pagt install
```

Spotless/Google Java Format 工具需要 JDK 17，独立的 `agt-format` profile 只在 JDK 17+ 自动启用。
这只影响开发时格式化；JDK 8 的 `mvn -Pagt verify` 仍会编译并执行完整测试，不跳过任何测试。
格式检查可在 JDK 17 下执行 `mvn -N spotless:check`。

业务项目引入 `org.agentguard:agent-guard-agt:1.0.0-SNAPSHOT`，它传递依赖参考引擎与复制的 AGT core。
框架只需要依赖 spec，并落实六阶段 hook 与 payloadOrThrow；已完成适配的框架可以通过 GuardEngineFactory 自动发现 SDK。
本次未修改外部 LangChain4j 或新增 Spring AI hook，未经适配的框架不会因引入 SDK 自动获得六阶段检查。

将 [可运行示例策略](../agent-guard-agt/src/test/resources/policy.yaml) 复制为业务配置，例如 `config/agt-policy.yaml`。
示例拒绝 `DENIED` 标记、将 `SECRET` 替换成 `MASKED`，并限制 lookup 工具的 read 权限与 tenant 参数。
这些标记只用于演示，不提供语义注入检测或自动 PII 识别。

```shell
java -Dagentguard.agt.policy-path=config/agt-policy.yaml -jar application.jar
```

| 配置 | 默认及语义 |
| --- | --- |
| `agentguard.agt.policy-path` | 无默认；宿主文件路径；显式设置时优先于环境变量 |
| `AGENTGUARD_AGT_POLICY_PATH` | 系统属性不存在时使用；无默认 |
| 路径为空、文件缺失、YAML 错误 | 工厂初始化失败，不启用空策略 |
| 缺少六阶段检查点 | Provider 初始化失败 |
| 审批要求 | 明确 BLOCK，reasonCode 为 approval_unsupported；不启动审批线程 |
| 故障与执行模式 | 默认参考引擎 ENFORCE/FAIL_CLOSED；应用显式改策略时遵循引擎语义 |

启动阶段读取一次配置，不支持自动热加载。工厂构造器保持轻量，不持有线程池或需要关闭的 AgentControl。
自动工厂通过已注册的 GuardProvider 组合规则；多个不同 GuardEngineFactory 仍按框架契约报冲突。
需要自定义组合、事件监听或使用 Java 策略时，可以显式构造：

```java
GuardEngine engine = new GuardEngineBuilder()
        .addProvider(new AgtGuardProvider(policy))
        .build();
```

`policy` 为线程安全的 `PolicyEvaluator`，通常使用 `StructuredPolicy.builder(...)`。
业务宿主每次请求创建独立 GuardContext，从认证结果注入 agentId、tenantId、userId，
并在 `StandardAttributes.PERMISSIONS` 放置 `Set<String>`。缺少权限按空集合处理；缺少身份或非法标识符属于执行故障。
AGT 标识符只接受 `[a-zA-Z0-9_.:@/-]` 且长度为 1–128；身份不从 prompt、工具参数或业务 sessionId 推断。
内部治理会话 ID 在请求共享 state 中生成，业务 sessionId 可以为空，各次 invocation 独立。

## 投影与改写边界

| 阶段 | 检查数据 | 支持改写 |
| --- | --- | --- |
| AGENT_INPUT | 消息与 JSON 模板变量 | 文本片段、同形消息工具参数及模板变量 |
| MODEL_INPUT | 所有历史消息、工具说明与 Schema、模型参数 | 现有消息文本与工具调用参数 |
| MODEL_OUTPUT | 完整消息、工具调用参数、模型及结束原因 | 文本、现有工具调用参数 |
| TOOL_INPUT | 解析后的 JSON object 参数，工具名独立传递 | 参数；保持 callId/toolName |
| TOOL_OUTPUT | 文本、调用标识、工具名及 error 标志 | content；保持身份及错误标志 |
| AGENT_OUTPUT | 文本与 JSON 结构化内容 | text；structured 必须保持不变 |

消息数量、角色、文本片段数量、工具调用数量及身份不能通过通用字符串替换修改。
MODEL_INPUT 的模型、工具集合/定义及参数可检查但暂不允许改写；JSON Schema 采用严格解析。
宿主 metadata 不作为内容策略目标，返回时保留原值；token usage 保留但不用于预算记账。
媒体、任意 POJO、非 JSON 模板变量/模型参数、非法工具 JSON 和不支持的结构改写均明确失败。
`structured` 为 JSON Map/List/标量时会完整扫描；改写 structured 暂时拒绝，避免 text 与结构化结果分裂。

工具参数改写后再次调用 validateEffective，审批需求也在改写后复查。AGT Guard 的 order 为 1000。
组合其他 Provider 时，参数改写规则需排在最终授权之前；order 不保证 AGT 永远最后，应用必须保证后续规则不会使授权失效。
强制授权不应配置为 OBSERVE、DISABLED 或 FAIL_OPEN；主动配置这些模式会遵循参考引擎的旁路契约。
框架拿到 GuardOutcome 后必须应用改写结果并调用 payloadOrThrow，不能只检查或记录 Finding。

## 验证

Java 8 兼容回归在原有 115 项基础上增加 6 项，覆盖集合工厂与复制、Stream 收集的 null 语义、
不可变类值比较及脱敏、Jackson 构造校验、严格 UTF-8 解码、异常 Future 和进程清理能力探测。
Provider 测试包含六阶段放行/拒绝/改写、完整历史与模型工具调用检查、租户权限、改写复查、
审批拒绝、身份与 JSON 故障、媒体/POJO 拒绝、并发隔离及真实 ServiceLoader 描述文件和 YAML 装配。
四个模块均要求生产 class major 为 52，复制的 AGT JAR 继续包含 META-INF/LICENSE 和 META-INF/NOTICE。

本次实际验证结果：

| 环境/检查 | 结果 |
| --- | --- |
| Zulu JDK 8 `clean verify` | 121 项通过，0 失败、0 错误、0 跳过 |
| Zulu JDK 17 `clean verify` | 同一源码 121 项通过，0 失败、0 错误、0 跳过；Spotless 通过 |
| 四个实际生产 JAR | 共 132 个 class，全部 major 52；AGT 许可与 SPI 描述文件保留 |
| JDK 8 外部调用方 | 仅依赖 spec 编译，实际 JAR SPI 自动发现、改写应用和越权工具阻断通过；副作用为 0 |
| 编码规范 | 49 个 Java 文件语法树检查通过；多行中文类型 Javadoc、花括号、无通配符导入、XML 结构及 diff 空白检查通过 |

未验证真实 LLM、外部框架端到端、生产负载或 Sonar。

## Java 8 兼容实现与命令边界

原有 22 个 record 改为 final 不可变类，保留原名访问器、equals/hashCode、显式脱敏 toString，
构造器通过 JsonCreator/JsonProperty 保持 YAML 字段绑定，并执行原有验证和防御性复制。
移除 var、模式匹配、switch 表达式和文本块；集合、文件、十六进制和失败 Future 的便利 API 由 Java8 类集中兼容。
Jackson 2.22.1 和 SnakeYAML 2.5 不因本次兼容改造而降版本。

固定命令执行在 JDK 8 上保留参数模板、摘要校验、清空环境、输出上限、超时、取消及主进程强制终止。
ProcessCleanup 隔离 Java 9+ 的 ProcessHandle 扩展：在支持的 JVM 上仍尽力清理可观察后代；
JDK 8 的标准 Process API 无法枚举后代，ProcessCleanup.supportsDescendants() 返回 false。
两种路径都不提供进程组、cgroup 或沙箱隔离保证；可能派生后台进程且需要完整清理的命令应使用专用 CommandBackend。
不能将主进程结束当成所有后代已退出。本次没有给 JDK 8 添加依赖 ps/kill 或私有 JVM 字段的生产实现。
POSIX 命令测试使用系统进程存活查询；Windows 上的命令测试未验证。
