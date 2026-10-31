# LangChain4j × Agent Guard AGT 示例

SDK 仓库内的独立 Maven 示例应用，引用本地修改版 LangChain4j 与本地 `agent-guard-agt` SDK，不参与 SDK 默认 reactor 构建。
提供两种配置方式：`GuardDemo` 通过 Java SPI 加载 YAML 策略；`CodePolicyDemo` 用 Java Builder 创建策略并显式传入引擎。
程序从 `.env` 读取配置，调用真实的 OpenAI 兼容 Chat Completions 接口。业务工具返回固定演示订单，不访问业务系统。

## 启动

需要 JDK 17+、Maven 3.8+，模型必须支持非流式工具调用。
示例位于 SDK 的 `agent-guard-example/` 子目录；默认 `agent-guard` 与 `langchain4j` 为同级目录。
先进入 `agent-guard-example/`，再执行下列命令。LangChain4j 使用包含护栏改动的 `codex/agent-guard-integration` 分支。

```shell
# 1. 编译并安装两个本地源码项目，避免使用远程同名 SNAPSHOT。
./scripts/install-local.sh

# 2. 编辑已生成的 .env，将占位值替换为真实模型配置。
# 如果 .env 丢失，可执行：cp .env.example .env

# 3. 先执行不消耗外部额度的测试。
mvn -nsu test

# 4. 使用真实模型执行全部演示场景。
./scripts/run.sh

# 或运行纯代码策略案例，不读取 YAML 策略文件。
./scripts/run-code-policy.sh
```

也可在 IDE 中直接运行 `example.GuardDemo.main`，工作目录设为项目根目录。
命令行传入第一个参数可以指定其他 dotenv 文件路径。
本机 `mvn` 版本过旧时，通过 `MAVEN_CMD` 指定 Maven 3.8+ 的可执行文件。
源码目录可通过 `AGENT_GUARD_SOURCE`、`LANGCHAIN4J_SOURCE` 覆盖。
安装脚本运行 SDK 的现有测试；框架安装跳过测试、覆盖率、格式化和与上游发行版的 Revapi 比较，仅生成本地制品。
本地 fork 新增的 Agent Guard 公开类型会触发上游 API 比较，此处不修改框架规则，也不代表框架完整质量门禁已通过。

`.env` 已忽略，`.env.example` 可公开提交。程序不打印密钥，不开启 HTTP 请求/响应日志。
缺少密钥或仍是占位值时立即报错，不回退到模拟模型。请勿把真实密钥提交到仓库。

## 配置

系统环境变量优先于 dotenv 文件，支持空行、整行 `#` 注释、`KEY=value` 和成对单/双引号。
不支持 `export`、变量展开、转义解释或行尾注释。dotenv 不会作为 shell 执行。

| 参数 | 说明 | 默认/空值 |
| --- | --- | --- |
| `OPENAI_BASE_URL` | OpenAI 兼容接口根地址，例如 `https://api.openai.com/v1` | 必填，不含 `/chat/completions` |
| `OPENAI_API_KEY` | 该接口的密钥 | 必填，样例占位值会报错 |
| `OPENAI_MODEL_NAME` | 支持工具调用的模型名称 | 必填，示例为 `gpt-4o-mini` |
| `LLM_TIMEOUT_SECONDS` | 每次请求超时，单位秒 | 默认 60，范围 1–600 |
| `AGENTGUARD_AGT_POLICY_PATH` | 仅 YAML/SPI 案例使用；宿主上的策略路径，相对于工作目录 | 默认 `config/agt-policy.yaml`，空值报错 |

为便于定位问题和控制费用，真实客户端重试次数为 0；工具循环最多两轮。
示例成功时通常执行 4 次模型请求；真实模型的行为可能有差异，拒绝调用工具或不按参数调用将使断言失败，不能视为权限验证通过。

## 演示与验收

每个场景都有运行时断言，失败会使程序以非成功状态结束。

| 场景 | 检查证据 |
| --- | --- |
| 输入包含 `DENIED` | `AGENT_INPUT` 阻断，模型请求和工具执行均为 0 |
| 查询 tenant-A 的 ORD-1001 | 真实模型调用 lookup，固定订单返回后模型生成非空回答 |
| 输入含 `DEMO_PRIVATE_INPUT` | 送往模型的请求包含 `MASKED_INPUT`，不包含原标记 |
| 工具返回 `DEMO_PRIVATE_TOOL` | 下一轮模型请求包含 `MASKED_TOOL`，不包含原标记 |
| 查询 tenant-B 的 ORD-9999 | 模型生成工具调用后，在 `TOOL_INPUT` 阻断，原因为 `context_binding_denied`，工具无新增执行 |
| 当前身份缺少 `orders:read` | `TOOL_INPUT` 阻断，原因为 `permission_denied`，工具无新增执行 |

输入拒绝场景排在最前，可验证 SDK 没有加载时程序不会宣称护栏生效。
正常工具循环经过 Agent 输入、模型输入/输出、工具输入/输出、Agent 最终输出六个检查位置。
计数器记录客户端接收的请求及真实进入业务工具的方法次数；测试还检查实际 HTTP 请求体中的脱敏结果。

可信身份由宿主通过 `guardContextFactory` 注入。本例固定为 `tenant-A` / `demo-user`，权限来自应用代码，不能从 prompt、模型结果或工具参数推导。
系统提示要求模型原样传递用户指定的租户，是为了主动演示服务端拦截；它不是安全边界。
策略中的字符串匹配和替换仅用于演示，不提供通用提示词注入检测或自动 PII 识别。
当前 AGT 桥接只复用策略层；审批、预算、流式治理不属于本示例验证范围。

## 代码入口与依赖

- `src/main/java/example/GuardDemo.java`：YAML/SPI 入口及共享的真实模型、AI Service、可信身份、工具和演示断言。
- `src/main/java/example/CodePolicyDemo.java`：纯 Java 策略入口，代码创建 Provider 与引擎，不读取 YAML。
- `src/main/java/example/LlmConfig.java`：配置读取与校验。
- `config/agt-policy.yaml`：六阶段策略、工具权限、租户绑定和脱敏规则。
- `src/test/java/example/GuardDemoTest.java`：本地 HTTP 协议桩与配置测试，仅测试源码使用协议桩。

```text
业务应用
├── langchain4j → agent-guard-spec
├── langchain4j-open-ai → HTTP 客户端
└── agent-guard-agt → agent-guard-core → agent-guard-spec
                   → agt-core
```

引入 AGT SDK 已包含 SPI 描述文件，无需应用重复注册。
显式 `.guardEngine(...)` 会覆盖自动发现；多个 SDK 工厂会导致构建失败。
运行脚本使用 `-nsu` 避免检查远程 SNAPSHOT 更新，仍须先安装本地源码。

## 验证边界

`mvn test` 使用本地 HTTP 服务模拟 OpenAI 协议响应，验证真实 HTTP 客户端、SPI 自动装配、工具循环和阻断/改写行为，不等于真实 LLM 验证。
`./scripts/run.sh` 或 `./scripts/run-code-policy.sh` 才会连接 `.env` 配置的外部模型，产生模型调用费用。
生成示例时未提供真实凭证，因此不宣称已完成外部模型验证。

调用链与调试图解：[LangChain4j 与 Agent Guard AGT 调用链及调试指南](docs/langchain4j-agt-call-chain.md)。

框架接入：[新 Agent 框架接入 Agent Guard：生命周期与代码指南](docs/agent-framework-integration-guide.md)。

纯代码案例：[不读取 YAML 的 Java 策略实现](docs/code-policy-demo.md)。


流式案例：[TokenStream 窗口与完整缓存护栏](docs/streaming-usage.md)。填写 .env 后运行 `./scripts/run-streaming.sh window` 或 `./scripts/run-streaming.sh strict`。


## 普通流式案例：不启用安全护栏

[PlainStreamingDemo](src/main/java/example/PlainStreamingDemo.java) 只配置模型、普通业务工具与流式回调。它使用框架默认关闭的自动发现，无需显式关闭，也不会因为同项目依赖 AGT SDK 而启用策略；不创建引擎、不配置流式护栏、不读取 YAML 或身份权限。

填写同一份 `.env` 后运行：

```shell
./scripts/run-plain-streaming.sh
# 等价命令：
mvn -nsu -Dexec.mainClass=example.PlainStreamingDemo compile exec:java
```

默认执行普通订单查询。也可在 IDE 中运行 `example.PlainStreamingDemo.main`，工作目录为项目根目录；第一个程序参数为 dotenv 路径，后续参数为提示词，例如 `.env 你好，请介绍一下自己`，日常问答不需要调用工具。

新增 [PlainStreamingDemoTest](src/test/java/example/PlainStreamingDemoTest.java) 用本地 SSE 服务验证普通问答和工具循环：首段在上游完成前到达、普通问答工具执行 0 次、订单查询工具执行 1 次并发起下一轮模型请求。即使 SDK 策略路径无效也可正常运行，验证默认没有进行自动发现。

```shell
mvn -nsu -Dtest=PlainStreamingDemoTest test
```

测试不消耗外部模型额度；脚本和 main 仍调用 `.env` 中配置的真实模型，本次未运行外部 LLM。


### 护栏启用开关

框架默认不扫描安全 SDK，普通模型调用直接 build 即可。需要 YAML/SPI 护栏时手动开启：

```java
AiServices.builder(Assistant.class)
        .chatModel(model)
        .guardAutoDiscovery(true)
        .build();
```

显式 `.guardEngine(engine)` 本身表示手动启用该引擎，优先于 SPI；纯代码案例和流式策略案例沿用此方式。`.guardAutoDiscovery(false)` 只关闭发现，不移除已经初始化或显式传入的引擎。

## 护栏配置诊断

同步 YAML/SPI、纯代码和带护栏流式案例在构建服务后打印 `AgentGuardDiagnostics` 摘要，可区分手动/SPI 来源、规则数量及当前流式模式是否通过构建检查。默认摘要不包含策略文件路径或内容。

需要自行查看失败状态时保留 builder：

```java
AiServices<Assistant> builder = AiServices.builder(Assistant.class)
        .chatModel(model)
        .guardAutoDiscovery(true);
try {
    Assistant assistant = builder.build();
    System.out.println(builder.guardDiagnostics());
} catch (RuntimeException error) {
    System.out.println(builder.guardDiagnostics());
    throw error;
}
```

缺少策略时仍初始化失败。CONFIGURED 表示装配与构建检查完成；并不表示某次请求已经执行全部规则。`engine().detailsAvailable()` 为 false 时元信息未知，空列表不能解释为没有规则。`streamingValidated()` 只对应当前流式配置，不表示支持任意流式模式。示例自动测试使用本地 HTTP/SSE，不表示真实 LLM 已验证。

## 更简洁的纯代码策略

CodePolicyDemo 使用 `configure(COMMON_RULES)` 复用公共内容策略，`tenantTool("lookup", "orders:read", "tenant", ...)` 同时声明权限与租户绑定，再通过 `requireStrings("orderId")` 声明参数。批量脱敏接受 Map；有多条且依赖执行顺序时使用 LinkedHashMap。

运行方式仍为 `./scripts/run-code-policy.sh`，模型从本地 .env 读取；流式入口复用同一套代码策略。权限和身份仍由可信上下文提供，未配置的工具/字段继续拒绝，快捷入口不推断工具是否只读。
