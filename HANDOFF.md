# Agent Guard 开发交接

更新时间：2026-10-28（Asia/Shanghai）。此文件是仓库事实快照，新会话先阅读并复核 Git 状态。

## 当前 Git 状态与范围

| 仓库 | 分支 | 当前基线提交 | 本次开始时状态 |
| --- | --- | --- | --- |
| 本仓库 `agent-guard` | master | `3bc019a` | 干净，无远端配置、无 upstream |
| 关联 `langchain4j` | main | `d7c5c5648` | 干净，相对本地 origin/main 引用领先 4 个提交；未 fetch，不能认定远端实时状态 |
| 关联 `agent-guard-example` | main | `f9942ac` | 干净 |

上述 Git 基线仍未变化；表格“本次开始时状态”描述建立交接机制前的状态。当前三个仓库有本轮配置诊断的未提交改动：SDK 新增诊断 API、元信息、测试与文档；LangChain4j 新增构建诊断与回归；示例显示摘要并增加集成测试。此前的 AGENTS/HANDOFF/PLANS 和 .gitignore 也仍未提交。未提交、未推送；POM 未修改。以后更新此文件时用实际提交替换基线。

## 最近工作和已完成能力

- `5fbe0d7`：AGT Fluent 策略构建及接入指南，`AgtGuards.builder()` 可纯代码构造引擎。
- `53e09e8`：可选流式 SPI、STRICT 有界完整缓冲、复用 AGT ChunkBufferEngine 的 WINDOW 字面窗口。
- `3bc019a`：SDK 文档同步框架显式开启自动发现的契约。
- LangChain4j 已有六阶段接入、TokenStream 检查和工具执行延后；最近提交默认关闭 SPI 自动发现，用户显式开启或手动设置引擎。
- 示例仓库有 YAML/SPI、纯代码策略、带护栏流式和不带护栏流式入口；测试使用本地 HTTP/SSE 服务。真实外部 LLM 是否可用仍取决于本地配置。
- 已建立会话入口、交接更新规则、长期计划与跨 Mac 恢复步骤，并补充忽略规则。
- 本轮已实现配置诊断：可选 DiagnosableGuardEngine、不可变 GuardDiagnostics、AGT 策略来源类别，以及 LangChain4j builder.guardDiagnostics()。区分关闭、未发现 SDK、手动/SPI、失败类别、已校验流式模式。快照不执行 Guard，默认摘要不含路径、策略或任意标签。
- 同步及流式示例已展示摘要；新增真实 SPI/环境变量和示例 classpath 集成回归。实现与使用方式见 [配置诊断契约](docs/configuration-diagnostics.md)。

## 进行中的任务

本轮配置诊断已实现并通过下述测试；代码和交接文件留在工作区，未提交或推送。没有其他正在实施的业务任务。下一阶段流式性能/可靠性优化仍为待选任务。

## 重要技术决策

1. 四模块 SDK 以 Java 8 为生产基线；开发格式化使用 JDK 17。spec 保持零第三方依赖，框架只依赖 spec。
2. 自动装配链：框架显式开启 → GuardEngineFactory SPI → AgtGuardEngineFactory → DefaultGuardEngineFactory → GuardProvider → DefaultGuardEngine。core 自身不发布工厂，自动装配不接受空 Guard 列表。
3. AGT 自动 Provider 的策略路径优先取系统属性 `agentguard.agt.policy-path`，缺少属性才读 `AGENTGUARD_AGT_POLICY_PATH`；路径为空/缺失/错误或不含全部六阶段时初始化失败，不自动创建空策略。纯代码策略通过手动引擎接入。
4. 框架必须使用有效载荷并落实阻断；身份与权限来自可信宿主。参数改写需先于最终授权。
5. STRICT 保留全文检查/改写能力；WINDOW 仅用于受支持的字面输出拒绝规则，不保证任意语义全文安全，也不能撤回已发送前缀。
6. 复制 AGT 保留坐标、包名和许可；桥接不启动 AgentControl，不能承诺底层所有治理能力已接入。

## 未解决问题与明确限制

- SDK 没有配置远端，本轮交接文件也未提交，因此当前尚未通过 Git 迁移到另一台 Mac。后续同步需要用户授权。
- 已有配置快照；它展示已注册 Guard，不展示 AGT 内部内容条目，也不证明某次请求执行了全部规则。第三方引擎未实现可选接口或元信息读取故障时详情未知。动态策略和 supports 的请求级生效情况需通过运行时事件查看。缺策略仍初始化失败。
- WINDOW 有规则组合限制；Flow.Publisher、媒体及 Spring AI 适配尚未实现。
- 自动工厂只在初始化时读取配置，无策略热加载。AGT 桥接审批需求当前阻断；预算等执行治理没有接入宿主生命周期。
- LangChain4j 仍依赖修改过的 fork；能否抽出独立适配模块需要验证公开扩展点，尚无验证结论。
- 仓库未跟踪 Maven Wrapper 或 CI 工作流；构建依赖机器上的 Maven/JDK。跨仓安装脚本要求 Maven 3.8+，不能依赖本机 Maven 缓存自动迁移。
- 未验证生产负载、外部真实 LLM、整个 LangChain4j reactor 或 Sonar。
- 老文档保留各阶段历史测试数（如 121/164/9），不能拿来代表当前总数。

## 测试证据

### 本轮配置诊断（2026-10-28）

- SDK 真实 ARM64 Zulu JDK 8：`mvn -B clean verify`，149 项通过（core 25、agt-core 87、Provider 37），0 失败、0 错误、0 跳过。含环境变量子进程及真实 SPI 来源测试。
- SDK JDK 17：`mvn -B install`，同样 149 项通过，Spotless 通过。四个生产 JAR 共 150 个 class，全部 major 52，复制 AGT 的 LICENSE/NOTICE 保留。16 个新增/修改 Java 文件通过语法树花括号、多行中文类型 Javadoc、无通配符导入及 Tab 检查；Markdown 链接/围栏与 diff 检查通过。
- JDK 8 选择注意：本机默认 `java_home -v 1.8` 选到了无编译器的 Oracle JRE，首次构建因此失败；改为已安装的 `java_home -v 1.8 -a arm64`（Zulu JDK）后完整通过。其他机器应先确认 `$JAVA_HOME/bin/javac -version`，不直接照抄架构参数。
- LangChain4j JDK 17、Maven 3.9.9：`mvn -B -nsu -pl langchain4j '-Dtest=*AgentGuardTest,AgentGuard*Test,AiServicesNonBlockingTest,StreamingToolResultCorrelationTest,StreamingAiServicesWithAsyncToolsTest,StreamingOutputGuardrailBufferDrainTest,OutputGuardrailChatRequestTest,OutputGuardrailWithoutChatMemoryTest,ToolServiceTest' -Djacoco.skip=true -Drevapi.skip=true test`，153 项通过，0 失败、0 错误、0 跳过；不是整个 reactor。Spotless 对六个修改文件执行检查。
- 框架测试通过后本地 install（跳过重复测试及 Revapi），示例执行 `mvn -B -nsu test`：13 项通过，0 失败、0 错误、0 跳过。使用本地 HTTP/SSE；没有调用外部真实 LLM。
- SNAPSHOT 安装与依赖它的编译必须顺序执行；本轮曾在写入 spec JAR 时并发编译出现读取错误，安装结束后按顺序重跑通过。

### 建立交接机制时的基线（历史）

2026-10-28 本次在当前 SDK 基线执行：

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 17)" mvn -B verify
```

结果：BUILD SUCCESS，Spotless 通过；根据本次 Surefire 报告汇总：

| 模块 | 测试 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| agent-guard-spec | 0 | 0 | 0 | 0 |
| agent-guard-core | 19 | 0 | 0 | 0 |
| agt-core | 87 | 0 | 0 | 0 |
| agent-guard-agt | 34 | 0 | 0 | 0 |
| 合计 | 140 | 0 | 0 | 0 |

这是增量 `verify`，不是本次 `clean verify` 或 JDK 8 重跑。详细报告位于各模块 `target/surefire-reports/`，属于本地产物，不随 Git 迁移。

历史证据：[流式契约](docs/streaming-design.md) 记录 SDK 真正 JDK 8 clean verify 的 140 项结果。上一轮开发会话记录 LangChain4j 相关回归 168 项、示例 11 项通过；本次未重跑这两个仓库，另一台 Mac 应重新验证，不能把历史结果视为新环境已通过。

本次交接文件的相对链接、Markdown 围栏、忽略规则正反例及 `git diff --check` 均通过；已跟踪文件未发现本次扫描覆盖的敏感文件名、常见 API Key 与私钥头模式。这是模式扫描，不保证识别所有形式的秘密。

## 换 Mac 的恢复步骤

1. 先同步三个仓库的目标提交和交接文件。当前未执行任何提交、配置远端或推送；仅复制 Codex 聊天不能迁移源码。
2. 可以把三个仓库放在同一父目录：`agent-guard/`、`langchain4j/`、`agent-guard-example/`；路径可含空格，命令引用变量时始终加双引号。
3. 在新 Mac 安装 JDK 17、Maven 3.8+；需要验收 SDK Java 8 时再安装 JDK 8。执行 `git status --short --branch`、`git log -1 --oneline` 与 `mvn -v`，核对版本表。
4. 在 SDK 根目录执行 `mvn verify`。集成运行需要从源码重新安装本机 SNAPSHOT，不复制旧机器的 target 或整个 Maven 缓存。
5. 进入示例仓库，阅读其 README，执行以下现有脚本：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/install-local.sh
mvn -nsu test
```

脚本先安装 SDK，再安装所需框架模块。LangChain4j 安装步骤跳过测试、格式与 Revapi 等门禁，仅用于准备本地制品，不代表框架验证通过。非同级目录可通过 `AGENT_GUARD_SOURCE`、`LANGCHAIN4J_SOURCE` 指定源码根目录，通过 `MAVEN_CMD` 指定 Maven 可执行文件，均为本机配置。

6. 真实 LLM 在示例目录从 `.env.example` 创建本地 `.env`，自行填入端点、模型、密钥；不把密钥写进交接文档。根据示例 README 选择 `run.sh`、`run-code-policy.sh`、`run-streaming.sh` 或 `run-plain-streaming.sh`。
7. 新会话给 Codex 的入口可以是：“先阅读 AGENTS.md 和 HANDOFF.md，核对三个仓库状态，汇报差异，再继续我指定的任务。”

### 没有远端时如何传递 Git 历史

以下仅为后续操作说明，本次没有执行。用户批准并完成需要迁移的提交后，可在每个仓库分别生成 bundle，例如 SDK：

```sh
git bundle create ../agent-guard.bundle --all
# 将 bundle 安全复制到另一台 Mac 后，在其父目录执行：
git clone -b master ./agent-guard.bundle agent-guard
```

另外两个仓库用各自名称及 main 分支。bundle 只包含已有提交与引用，不含未提交文件或 `.env`。已有目标 checkout 时先 `git bundle verify`、检查目标工作区，再按需 fetch/合并，禁止直接重置覆盖本地改动。以后采用远端同步时，实际地址由用户提供，不在共享文档记录带凭据的 URL。

## 下一步任务

配置诊断已交付。建议下一轮盘点流式边界测试覆盖、补充性能与生命周期基准，再决定具体优化。后续拆分和验收见 [PLANS.md](PLANS.md)。尚未实施的事项仍为候选路线。

## 相关文件

- [协作约定](AGENTS.md)、[长期计划](PLANS.md)、[项目入口](README.md)、[构建配置](pom.xml)。
- [配置诊断契约与用法](docs/configuration-diagnostics.md)：新增规范接口位于 `agent-guard-spec/src/main/java/org/agentguard/diagnostics/`；SDK 新测试为 `GuardDiagnosticsTest`、`AgtDiagnosticsTest`；框架修改 `AiServices`、`AiServiceContext`、`DefaultAiServices` 和 guard 包的自动配置/诊断类；示例新测试为 `example.GuardDiagnosticsTest`。
- [设计契约](docs/design-contract.md)、[自动装配](docs/sdk-auto-configuration.md)、[流式设计](docs/streaming-design.md)。
- [Fluent API](docs/agt-fluent-api.md)、[AGT 实现边界](docs/agt-implementation.md)、[调用链](docs/langchain4j-agt-call-chain.md)。
- 核心入口：`agent-guard-core/src/main/java/org/agentguard/core/DefaultGuardEngine.java`、`DefaultGuardEngineFactory.java`、`GuardEngineBuilder.java`（后两者同目录）。
- 策略入口：`agent-guard-agt/src/main/java/org/agentguard/agt/AgtGuards.java`、`AgtGuardProvider.java`、`AgtWindowStream.java`（同目录）。
- SPI 资源：`agent-guard-agt/src/main/resources/META-INF/services/`；测试：core/Provider/复制 AGT 模块各自的 `src/test/java/`。

## 每次收尾更新清单

更新日期、实际分支和提交；分别写清已完成、进行中、未解决；记录重要决策、下一步和相关文件；列出实际运行的命令、测试范围与结果；检查敏感内容及未提交文件。用户未授权提交时保留工作区，并明确说明新 Mac 尚不能通过 Git 获取这些文件。
