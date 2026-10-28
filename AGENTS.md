# Agent Guard 开发协作约定

## 每次新会话的入口

1. **先阅读根目录 [HANDOFF.md](HANDOFF.md)**，确认最近进度、限制和测试证据，再阅读 [PLANS.md](PLANS.md)。
2. 执行 `git status --short --branch`、`git log -5 --oneline`，以实际工作区为准；交接记录是快照，不能覆盖新的代码事实。
3. 涉及关联仓库时分别检查其状态和提交。不得根据本机 Maven 缓存判断源码已同步。
4. 开始软件开发、配置、调研或文档任务前，若本机存在 `~/.codex/skills/umuo-workstyle/SKILL.md`，先读取。另一台 Mac 未安装该技能时，仍遵守本文件的独立约束。
5. 先说明当前任务范围与验收；长期计划中的建议不代表用户已授权实施。提交、推送、发布和改写历史按当前用户授权执行。

## 项目与架构

这是框架无关的 Java Agent 安全护栏 SDK，Maven reactor 包含四个模块：

| 模块 | 职责与依赖 |
| --- | --- |
| `agent-guard-spec` | Java 8、零第三方依赖；模型、六阶段 SPI、Engine 门面、决议、上下文、事件和可选流式接口 |
| `agent-guard-core` | 依赖 spec；参考引擎，负责排序、链式改写、执行模式、失败策略、审计事件和流缓冲 |
| `agent-guard-agt` | 依赖 core 与 agt-core；AGT 六阶段投影、策略桥接、Fluent Builder、SPI 工厂、窗口检查 |
| `agt-core` | 复制并适配到 Java 8 的 AGT 策略及治理源码，保留 `io.agt` 包名与独立坐标 |

构建使用 Maven；策略序列化使用 Jackson（agt-core 的 BOM 为 2.22.1）与 YAML 支持。项目没有独立 Web 服务启动入口，运行演示位于关联示例仓库。

可选 `agent-guard-benchmarks` 仅在 `-Pbenchmarks` 时参与构建，使用 JMH 1.37 测量流式内核及 SDK 生命周期，不属于生产 SDK 依赖。

SDK 坐标版本为 `org.agentguard:*:1.0.0-SNAPSHOT`；复制模块为 `io.agt:agt-core:0.6.0-SNAPSHOT`。不得同时混用另一个同坐标 AGT 实现。保留 `agt-core/LICENSE`、`NOTICE` 和 `UPSTREAM.md`。

框架侧仅依赖 spec，宿主引入具体 SDK。已适配的 LangChain4j 在显式 `.guardAutoDiscovery(true)` 时经 `GuardEngineFactory` → `DefaultGuardEngineFactory` → `GuardProvider` 创建引擎；默认不开启。手动 `.guardEngine(engine)` 可直接启用。core 自身不注册自动工厂。

## 行为约束

- 六阶段：AGENT_INPUT、MODEL_INPUT、MODEL_OUTPUT、TOOL_INPUT、TOOL_OUTPUT、AGENT_OUTPUT。框架必须执行对应检查，并使用 `payloadOrThrow()` 返回的有效载荷，落实改写和阻断。
- 违规与检测故障区分：`GuardBlockedException` 与 `GuardExecutionException` 不合并；不得通过静默过滤或放行掩盖故障。
- 参数改写应在最终授权之前；工具身份不得改写，改写后按既有契约再次校验。可信身份、权限来自宿主认证，不来自 prompt。
- 自动 AGT Provider 需要策略路径及完整六阶段检查点；缺少配置初始化失败。纯代码 `AgtGuards.builder()` 可以绕过 YAML 工厂，直接构造引擎。
- STRICT 完整缓存后检查和释放；WINDOW 只覆盖受支持的字面拒绝规则，末尾仍检查全文，已发送内容无法撤回。不得宣称有限窗口保证任意语义安全。
- 当前 LangChain4j 流式适配只支持显式配置的 TokenStream；Flow.Publisher 与护栏组合明确拒绝。媒体和 Spring AI 适配尚未实现。
- 不因底层 AGT 存在预算、审批等类型就声称 SDK 已接入相应运行时；桥接不启动 AgentControl。
- 具体变更先对照 [设计契约](docs/design-contract.md)、[AGT 契约](docs/agt-implementation.md)、[流式契约](docs/streaming-design.md)。

## 编码规范

- 默认每级 4 空格、不用 Tab；仅项目格式化配置明确锁定时例外。
- `if`、`else`、`for`、`while`、`do` 等控制语句始终使用花括号并换行，包括单行卫语句。
- 新增或修改的 Java 类、接口、枚举、Record 均有多行中文 Javadoc，`/**`、`*/` 独占行；声明注解独占行；禁止通配符导入。
- 生产 SDK 保持 Java 8 源码与运行时兼容，不能引入 record、新版语法或 Java 9+ 必需 API。不能仅依靠 target=8 声称运行时兼容。
- XML 父子容器分行并逐级缩进，仅纯文本叶节点允许单行。
- 按职责组织包；优先复用现有 API。新复杂能力先写轻量规格及验收、验证成熟方案，再实现；不扩大为全仓重构。
- 文档使用中文，技术名称保留原名；仓库内容使用相对路径或环境变量，禁止写入用户名、固定磁盘路径或真实业务数据。

## 开发和测试命令

在 SDK 根目录执行，先用 `mvn -v` 确认实际 JDK。Mac 推荐明确选择 JDK 17：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -v
mvn verify
mvn install
```

`verify` 包含编译、测试、打包；`install` 另外安装到本机 Maven 仓库，供框架/示例使用。`mvn test` 可快速回归；`mvn -Pagt verify` 是兼容入口，默认已经包含四模块。

```sh
# 指定模块及其依赖
mvn -pl agent-guard-agt -am test
# JDK 17 下检查或修复当前配置覆盖的格式
mvn -N spotless:check
mvn -N spotless:apply
# Java 8 兼容验收，先确认选中的是包含 javac 的 JDK 而非 JRE
export JAVA_HOME="$(/usr/libexec/java_home -v 1.8)"
"$JAVA_HOME/bin/javac" -version
mvn clean verify
```

- core/Provider 使用 JUnit 4，复制的 agt-core 使用 JUnit 5；测试报告在各模块 `target/surefire-reports/`。
- JDK 17+ 自动启用 `agt-format`；Spotless 仅覆盖 agt-core 与 agent-guard-agt，不代表全部 Java 已检查。花括号、中文类型 Javadoc、无通配符导入、XML 结构仍需自检。
- 新业务规则与状态机改动需覆盖放行、拒绝、改写、失败和关键边界。流式改动覆盖跨片段、终态、取消、并发、缓冲上限及工具副作用；纯文档修改检查链接、命令和 diff 即可。
- 发布 Java 8 兼容结论需真实 JDK 8 回归，并检查产物 class major 52。JDK 17 单次通过不替代该结论。
- 关联框架回归与真实 LLM 验证分别报告；本地 HTTP/SSE 桩不能算真实 LLM。跳过测试的依赖安装不能算验收。
- 不为获得绿灯关闭规则；未运行的检查明确写未运行，不能声称通过 Sonar 或全框架测试。

性能验证使用 [流式基准指南](docs/streaming-performance.md) 的 JMH/探针命令；保留输入规模、JDK、预热、fork 和误差，不以一次计时或分配量代替稳定延迟/峰值堆结论。

## 跨 Mac 交接与收尾

- `AGENTS.md` 保存稳定约束；`HANDOFF.md` 保存最新事实；`PLANS.md` 保存长期路线和验收条件，避免三份重复流水账。
- 本地 SNAPSHOT 安装完成后才能编译或测试依赖它的其他仓库，避免读到正在写入的 JAR。
- 每次任务结束或切换机器前更新 HANDOFF：日期、分支/提交、修改文件、已完成/进行中/未解决、决策、下一步、实际测试命令与结果。未完成就写未完成，历史证据标明来源。
- 交接文档不能依赖 Codex 聊天历史、临时目录、旧 Mac 路径或本机技能。密钥、个人配置留在本地，另一台 Mac 自行配置。
- 使用 Git 或受控 bundle 同步提交；未经提交的文件不会随 clone/bundle 迁移。任何 Git 写操作仍按用户授权执行，不自动提交或推送。
- 交接步骤与关联版本见 HANDOFF。最后执行 `git diff --check`、检查未跟踪文件与敏感内容，并报告真实状态。
