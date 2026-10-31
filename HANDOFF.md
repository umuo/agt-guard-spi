# Agent Guard 开发交接

更新时间：2026-10-31（Asia/Shanghai）。此文件是仓库事实快照，新会话先阅读并复核 Git 状态。

## 当前 Git 状态与范围

| 仓库 | 分支 | 当前基线提交 | 本次开始时状态 |
| --- | --- | --- | --- |
| 本仓库 `agent-guard` | master | 本次示例迁入提交（父提交 `90896eb`，准确 HEAD 用 git log 核对） | 用户已授权将示例目录与交接变更提交并推送至 `umuo/agt-guard-spi` 的 `master` |
| 关联 `langchain4j` | codex/agent-guard-integration | `5fcd835fa` | 干净；本地 main 已恢复至 `8f32a2fd5`；未 fetch，不能认定远端实时状态 |
| 内置 `agent-guard-example/` | 随本仓库 master | 原独立仓库基线 `dcafffa` | 现作为普通目录跟踪，不再需要独立 clone |

流式优化已在 `b9b1ac3` 本地提交；此前配置诊断为 SDK `9f79592`、框架 `5fcd835fa`、示例 `a0eee32`。用户已授权增加策略配置易用性并提交，本轮代码和交接记录纳入本次本地提交，未推送：SDK 新增组合回调、批量配置、租户工具快捷入口和错误位置提示；示例采用新 API。LangChain4j 源码未修改。

## SDK 远端同步（2026-10-31）

- 用户授权将当前目录文件提交并推送至 `git@github.com:umuo/agt-guard-spi.git`。操作前本地 `master` 为 `7edc78f`，未配置远端；通过 `git ls-remote --symref` 核对目标远端为空。
- 本次提交范围为 `HANDOFF.md` 与 `agt-core/UPSTREAM.md`，包含此前编译排查、AGT 回同步和 LangChain4j 分支整理记录。配置目标为 `origin`，提交后执行 `git push -u origin master`；推送结果以远端引用和实际 Git 状态核对为准。
- 已检查完整待提交 diff、未跟踪文件及已跟踪文件的常见密钥、敏感文件名和个人路径模式，未发现匹配项；此模式扫描不保证识别所有秘密。纯文档变更未重跑构建测试，收尾使用 `git diff --check` 与 Git 分支状态核验。示例仓库不在本次推送范围。
- 本节更新了此前“SDK 无远端、未推送”的历史快照；下文各开发阶段的测试结果与当时推送状态仍保留为历史证据。

## 示例迁入 SDK 仓库（2026-10-31）

- 用户已将示例移动至本仓库 `agent-guard-example/`，并授权提交、推送。原独立仓库为干净的 `main`、HEAD `dcafffa`，36 个受跟踪文件作为普通文件纳入 SDK，不使用 gitlink/submodule；保留原提交号作为来源记录。
- 原示例 `.git` 已移动至本机忽略目录 `.local-repo-backups/agent-guard-example.git`，历史可用 `git --git-dir=.local-repo-backups/agent-guard-example.git log` 查看。此目录不推送、不作为另一台 Mac 获取源码的前提；完整历史仍只保留在本地备份，远端包含导入快照。原 `.env`、IDE 配置和 target 均保留本地且被忽略。
- 调整示例安装脚本默认路径为上一级 SDK 及与 SDK 同级的 LangChain4j，保留环境变量覆盖；修正文档中的 SDK 源码相对链接，同步 README、AGENTS 和换 Mac 步骤。示例仍使用 JDK 17 独立构建，不添加至 SDK 的 Java 8 默认 reactor。此前“三个独立仓库”的描述为历史状态，以本节和当前恢复步骤为准。
- 迁入目录执行 JDK 17/Maven 3.9.16 的 `mvn -B -ntp -nsu clean test`：13 项通过，0 失败、0 错误、0 跳过；本地 HTTP/SSE，无真实 LLM。脚本 `bash -n`、示例 POM XML 解析、示例与入口 Markdown 本地链接、11 个 Java 文件的类型 Javadoc 形态及通配符/Tab/简短无花括号卫语句模式检查均通过。
- 完整 `scripts/install-local.sh` 首次在 SDK Spotless 插件解析阶段被本机旧 HTTP 镜像阻止；使用临时 HTTPS Central 镜像重试仍因 `No route to host` 失败，未进入 SDK 编译测试或框架安装。未修改全局 Maven 配置、未关闭检查规则；本次不宣称跨仓依赖重装通过。示例测试使用现有本地依赖，网络恢复后可重新安装。
- 提交范围同时包含此前示例 IDEA 索引排查和框架干净编译的交接记录。新文件的常见密钥、个人路径和敏感文件名模式扫描无命中，无缺失的本地 Markdown 链接；提交前检查忽略规则及 `git diff --check`，推送目标为 `origin/master`。后续在新目录重新打开 IDEA 项目；未自动启动真实 LLM 演示。

## 示例编译排查（2026-10-31）

- 用户反馈 `agent-guard-example` 编译失败。实际工作区干净，`main` 为 `dcafffa`；关联框架位于 `codex/agent-guard-integration`、HEAD `5fcd835fa`。未修改示例源码、POM 或 IDE 配置。
- 使用本机已有的 Maven 3.9.16 与 JDK 17 执行 `mvn -B -ntp -nsu clean test`，生产与测试源码编译成功，13 项测试全部通过、无失败或跳过。随后按 IDEA 日志所示的 Maven 3.6.3 与 JDK 25 再运行相同命令，也全部通过；旧 Maven 有运行时 API 警告，但本次未导致构建失败。两次均使用本地 HTTP/SSE，无真实 LLM 调用。
- IDEA 项目 SDK/默认语言级别为 25，本地默认 bytecode target 为 8；但 Maven external storage 的模块语言级别和 bytecode target 已为 17，因此不能仅凭本地默认配置认定失败原因。最新日志显示示例 Maven 导入使用 Maven 3.6.3/JDK 25，尚未取得本次实际编译报错；历史框架 Lombok 故障不能当成本次示例失败证据。
- 当前执行环境 PATH 找不到 `mvn`，验证使用已有 Maven 的绝对路径；这仅说明当前终端环境，不代表用户终端同样缺失。尚未复现用户报错，待用户提供失败入口和第一条错误后继续定位；未宣称已修复或完成 IDEA Build 验收。
- 用户随后确认失败入口为 IDEA。直接在示例窗口执行 Build → Rebuild Project，2026-10-31 08:53 的 Build 面板显示 `Build completed successfully in 2 sec, 402 ms`；生产和测试源码均经 javac 25.0.4.1 编译成功。未修改源码或 IDE 配置，当前 IDEA 完整重建已验收通过；未取得此前错误，不能确定历史根因，也不将编译成功解释为 IDEA 已执行测试。上条“IDEA Build 未验收”是重建前的排查状态。
- 用户进一步指出 `GuardDemo.java:120` 的 `builder.guardDiagnostics()` 编辑器爆红。IDEA Problems 实际显示 `Cannot resolve method 'guardDiagnostics' in 'AiServices'`，尽管编译成功；修改版源码及本地 sources JAR 均包含该方法。Reload All Maven Projects 后错误仍在；通过 Help → Find Action → Repair IDE 执行第一步 Refresh Indexable Files 后，方法解析错误消失，Problems 从 10 项降为 9 项，仅剩原有警告和提示；已确认 Everything Works Now。此次问题定位为 IDEA 索引/缓存状态未刷新，未修改源码、POM 或 JDK 配置，无需删除诊断调用。编译成功与编辑器解析正常已分别验证。

## LangChain4j 干净编译核验（2026-10-31）

- 用户询问关联框架能否构建。实际分支 `codex/agent-guard-integration`、HEAD `5fcd835fa`，操作前后工作区均干净。使用项目 Maven Wrapper 3.9.16，未修改源码或构建配置。
- JDK 17：`JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./mvnw -B -ntp -nsu clean compile`，默认 reactor 117 模块全部成功，36.231 秒。
- JDK 21：`JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./mvnw -B -ntp -nsu -pl langchain4j-jlama,langchain4j-jitllm -am clean compile`，12 模块全部成功，8.583 秒。
- JDK 25：`JAVA_HOME="$(/usr/libexec/java_home -v 25)" ./mvnw -B -ntp -nsu -pl langchain4j-gpu-llama3 -am clean compile`，11 模块全部成功，7.143 秒。
- 本轮结论为生产源码的干净编译通过，覆盖默认 reactor 及三个高 JDK 可选模块；未执行测试、package、verify、install 或本轮框架 IDEA Rebuild，不将其表述为全仓测试或发布门禁通过。`git diff --check` 通过。

## LangChain4j 分支整理（2026-10-31）

- 用户授权将已提交的护栏改动保留到新分支，并恢复本地主分支。已从 `5fcd835fa` 创建并切换至 `codex/agent-guard-integration`，完整保留五个 Agent Guard 提交；本地 `main` 恢复至这些改动之前的 `8f32a2fd5`，与当前本地记录的 `origin/main` 一致。
- 执行 `git switch -c codex/agent-guard-integration`、`git branch -f main 8f32a2fd5`；通过 `git status --short --branch`、`git branch -vv`、`git log --oneline main..codex/agent-guard-integration` 和 `git diff --check` 核验，框架工作区干净，提交差集恰为上述五项。未 fetch、未推送、未修改源码，未重跑构建测试。
- 用户随后提供个人 fork 并授权推送：官方远端由 `origin` 改名为 `upstream`，个人 fork `umuo/langchain4j-guard` 配置为 `origin`。执行 `git push -u origin codex/agent-guard-integration` 成功，新分支已推送并跟踪同名远端分支，HEAD 为 `5fcd835fa`；未推送或修改远端 `main`。本地 `main` 继续跟踪 `upstream/main`。
- 后续继续护栏开发使用新分支；另一台 Mac 可从个人 fork 获取该分支。本仓库仅更新交接，保留已有未提交文档，不创建提交。

## AGT Core 通用优化回同步（2026-10-31）

- 用户授权将本仓库 agt-core 的非 Java 8 优化同步至关联 `agent-governance-toolkit-4j`。目标实际分支为 `codex/langchain4j-native-integration`，HEAD `f672c1c`；开始时 `agt-core/pom.xml` 已有两行未提交改动，本轮原样保留。
- 根据提交历史和源码比较，通用优化来自 `b9b1ac3`：ChunkBufferEngine 的回调内定时器合并、原超时起点保留，以及 seal 异常统一关闭和单次失败通知。仅同步该生产类与九项生命周期回归；目标保留 List.copyOf、List/Set/Map.of、String.repeat，未引入 Java8/ProcessCleanup 或其他降级改造。
- 先在目标旧实现上运行九项新回归，四项复现失败；移植后 Core `clean verify` 90 项通过。目标完整 reactor `AGT_LIVE_TEST=false mvn -B -nsu verify`：508 项、0 失败、0 错误、2 项跳过，即 506 项实际通过；JDK 17、Maven 3.9.9。真实 LLM 未调用，性能基准未重跑。
- 目标新增 `docs/streaming-core-sync.md` 并从现有流式验收文档链接；本仓库更新 UPSTREAM 来源说明和本交接。保留此前编译排查的未提交 HANDOFF 改动；同步时未 install。用户随后授权提交并推送：目标提交 `92cb2fa` 已成功推送至 `origin/codex/langchain4j-native-integration`，目标工作区干净；本仓库文档仍未提交。同步代码已完成，目标 IDEA 实际重建仍属于此前待用户验证事项。

## 最近工作和已完成能力

- `5fbe0d7`：AGT Fluent 策略构建及接入指南，`AgtGuards.builder()` 可纯代码构造引擎。
- `53e09e8`：可选流式 SPI、STRICT 有界完整缓冲、复用 AGT ChunkBufferEngine 的 WINDOW 字面窗口。
- `3bc019a`：SDK 文档同步框架显式开启自动发现的契约。
- LangChain4j 已有六阶段接入、TokenStream 检查和工具执行延后；最近提交默认关闭 SPI 自动发现，用户显式开启或手动设置引擎。
- 示例仓库有 YAML/SPI、纯代码策略、带护栏流式和不带护栏流式入口；测试使用本地 HTTP/SSE 服务。真实外部 LLM 是否可用仍取决于本地配置。
- 已建立会话入口、交接更新规则、长期计划与跨 Mac 恢复步骤，并补充忽略规则。
- 本轮已实现配置诊断：可选 DiagnosableGuardEngine、不可变 GuardDiagnostics、AGT 策略来源类别，以及 LangChain4j builder.guardDiagnostics()。区分关闭、未发现 SDK、手动/SPI、失败类别、已校验流式模式。快照不执行 Guard，默认摘要不含路径、策略或任意标签。
- 同步及流式示例已展示摘要；新增真实 SPI/环境变量和示例 classpath 集成回归。实现与使用方式见 [配置诊断契约](docs/configuration-diagnostics.md)。
- 上述诊断与交接已提交：SDK `9f79592`、框架 `5fcd835fa`、示例 `a0eee32`。
- 本轮已修复 seal 拒绝/审计异常/正文不一致的资源清理与单次错误通知；accept 为剩余未检查内容最多安排一次定时器，保留原超时起点。
- 新增九个内核生命周期回归和两个 SDK 回归；可选 benchmarks profile 包含 JMH 与并发指标探针。前后数据、测量条件和局限见 [流式性能记录](docs/streaming-performance.md)。上述流式优化已在 `b9b1ac3` 提交。
- 本轮策略易用性：`configure(Consumer<Builder>)` 在独立草稿中组合公共配置；`tenantTool` 同时声明权限和可信租户绑定；Map 批量脱敏与 requireStrings/optionalStrings；重复和非法项提示到工具/字段位置。批量权限校验修复了失败后可能保留前几项的状态问题。
- 公共配置草稿、批量操作均失败不提交，已应用配置不受捕获草稿/Map 后续修改影响。新增 AgtPolicyConfigurationTest 的 11 项真实策略行为测试，示例 CodePolicyDemo 使用新 API。用法及边界见 [Fluent Builder](docs/agt-fluent-api.md#复用公共配置与批量声明)。

## 进行中的任务

本轮策略易用性已实现并通过下述测试，SDK 与示例代码/文档均本地提交，未推送。当前没有进行中的代码任务。SDK 提交号以包含本记录的提交为准；示例提交为 `dcafffa`。

## 重要技术决策

1. 四模块 SDK 以 Java 8 为生产基线；开发格式化使用 JDK 17。spec 保持零第三方依赖，框架只依赖 spec。
2. 自动装配链：框架显式开启 → GuardEngineFactory SPI → AgtGuardEngineFactory → DefaultGuardEngineFactory → GuardProvider → DefaultGuardEngine。core 自身不发布工厂，自动装配不接受空 Guard 列表。
3. AGT 自动 Provider 的策略路径优先取系统属性 `agentguard.agt.policy-path`，缺少属性才读 `AGENTGUARD_AGT_POLICY_PATH`；路径为空/缺失/错误或不含全部六阶段时初始化失败，不自动创建空策略。纯代码策略通过手动引擎接入。
4. 框架必须使用有效载荷并落实阻断；身份与权限来自可信宿主。参数改写需先于最终授权。
5. STRICT 保留全文检查/改写能力；WINDOW 仅用于受支持的字面输出拒绝规则，不保证任意语义全文安全，也不能撤回已发送前缀。
6. 复制 AGT 保留坐标、包名和许可；桥接不启动 AgentControl，不能承诺底层所有治理能力已接入。

## 未解决问题与明确限制

- SDK 当前远端同步授权与操作范围见上方“SDK 远端同步”；尚未在另一台 Mac 实际恢复构建，不将推送视为跨机器运行验证。
- 已有配置快照；它展示已注册 Guard，不展示 AGT 内部内容条目，也不证明某次请求执行了全部规则。第三方引擎未实现可选接口或元信息读取故障时详情未知。动态策略和 supports 的请求级生效情况需通过运行时事件查看。缺策略仍初始化失败。
- WINDOW 有规则组合限制；Flow.Publisher、媒体及 Spring AI 适配尚未实现。
- 自动工厂只在初始化时读取配置，无策略热加载。AGT 桥接审批需求当前阻断；预算等执行治理没有接入宿主生命周期。
- LangChain4j 仍依赖修改过的 fork；能否抽出独立适配模块需要验证公开扩展点，尚无验证结论。
- 仓库未跟踪 Maven Wrapper 或 CI 工作流；构建依赖机器上的 Maven/JDK。跨仓安装脚本要求 Maven 3.8+，不能依赖本机 Maven 缓存自动迁移。
- 本轮仅测量本机短基准与受控并发探针，耗时误差范围有重叠；未验证持续生产负载、跨机器稳定性能、外部真实 LLM、整个 LangChain4j reactor 或 Sonar。
- 老文档保留各阶段历史测试数（如 121/164/9），不能拿来代表当前总数。

## LangChain4j 编译排查（2026-10-28）

- 关联框架当前源码基线仍为 `5fcd835fa`，排查期间未修改业务代码。终端默认 Maven 3.6.3，在父 POM 的 RequireMavenVersion 校验失败：项目要求 `[3.8,)`；此时尚未进入框架源码编译。
- 使用 JDK 17 与 Maven 3.9.9，`mvn -B -nsu -pl langchain4j -am clean test-compile`：10 个模块全部成功，覆盖生产及测试源码编译；未执行测试。
- 框架已有 Maven Wrapper，`./mvnw -v` 确认使用 Maven 3.9.16。JDK 17 下执行 `./mvnw -B -nsu -DskipTests compile`，默认 reactor 的 117 个模块全部成功；此项不是全仓 clean、测试或 install 验收。
- 推荐在框架根目录用 `JAVA_HOME="$(/usr/libexec/java_home -v 17)" ./mvnw -B -nsu -DskipTests compile` 构建，避免依赖终端 PATH 中的旧 Maven。IDEA 的 Maven home 选择 Wrapper，Project SDK、Maven importer 和 runner JDK 统一为 17。本机 IDEA 原配置与后续实际故障证据、局部调整见下文。
- 用户补充 IDEA Build 报 `ExceptionInInitializerError / TypeTag :: UNKNOWN`。已读取本机 IDEA 构建日志确认：2026-10-28 15:21 的该项目构建使用 javac 25.0.4.1，LombokProcessor 初始化时因 `NoSuchFieldException: com.sun.tools.javac.code.TypeTag :: UNKNOWN` 失败。此故障独立于终端旧 Maven；不能以终端编译成功代替 IDEA 验收。
- 已将受 Git 忽略的 `.idea/misc.xml` 中 Project SDK 从 `azul-25` 调整为已注册的 `azul-17`，语言级别从 25 改为 17；未修改全局 JDK 或项目依赖。IDEA 已打开时需重新打开项目以加载磁盘配置，再执行 Build → Rebuild Project；本次不能自动操作 IDEA，实际 IDE 重建结果待用户验证。调整后用 JDK 17 + Wrapper 再次运行核心及依赖 `clean test-compile`，10 个模块全部成功（未执行测试）。Lombok 与编译器兼容性参考 [官方变更记录](https://projectlombok.org/changelog)，Java 编译器选择参考 [IDEA 官方文档](https://www.jetbrains.com/help/idea/specifying-compilation-settings.html)。
- 后续 IDEA 报 Jlama target 21 却 fallback SDK 8。源码证据：根 POM 的 `jdk21` profile 在 JDK ≥21 时添加 Jlama/Jitllm，`jdk25` profile 添加 GPU Llama3；Jlama/Jitllm 目标 21，GPU 目标 25。因此上面的 JDK 17 默认 reactor 117 模块成功不包含这三个可选模块，不能据此声称所有可选模块已通过。
- 验证：JDK 21 + Wrapper 执行 `-pl langchain4j-jlama,langchain4j-jitllm -am -DskipTests compile`，12 模块成功；JDK 25 + Wrapper 执行 `-pl langchain4j-gpu-llama3 -am -DskipTests compile`，11 模块成功。这两次为增量生产源码编译，未执行测试，不代替 IDEA Rebuild。
- 普通模块保留项目 SDK 17；在本机 IDEA 该项目的 external storage 模块配置中，给 `langchain4j-jlama`、`langchain4j-jitllm` 显式设置已注册的 `azul-21`，给 `langchain4j-gpu-llama3` 设置 `azul-25`。这些均是本地生成配置，不纳入 Git；重新导入 Maven 可能重建它们。跨 Mac 应在 Project Structure → Modules → Dependencies 为可选模块配置对应 SDK，并检查 Maven importer JDK 与激活 profile，不能统一将所有模块降至 17 或 8。IDEA 仍需重新加载项目后实际 Rebuild 验收。
- JDK 25 + Maven 3.9.9 的核心模块 `clean compile` 也成功，说明该定向 Maven 路径没有复现 IDEA 注解处理路径的故障，不能笼统断言所有 JDK 25 构建必失败。

## 测试证据

### 本轮策略易用性（2026-10-28）

- SDK 真实 ARM64 Zulu JDK 8：`mvn -B verify`；JDK 17：`mvn -B clean install`，最终错误提示调整后又运行 install。均 171 项通过（core 25、agt-core 96、Provider 50），0 失败、0 错误、0 跳过；JDK 17 Spotless 通过。
- 新测试覆盖公共配置复用、版本/规则/工具/生成 ID 回滚、捕获草稿隔离、Map 顺序和快照、批量原子性、权限/租户/闭合参数约束及错误位置与内容保护。保留已有空 Builder、重复规则、身份、YAML/SPI 和流式回归。
- SDK 安装结束后框架运行 `mvn -B -nsu -pl langchain4j '-Dtest=*AgentGuardTest,AgentGuard*Test' -Djacoco.skip=true -Drevapi.skip=true test`：62 项通过；本轮仅定向回归，不将上轮 153 项算作本轮运行结果。
- 更新后的纯代码示例及其他用例执行 `mvn -B -nsu test`：13 项通过，本地 HTTP/SSE 验证，无外部 LLM 调用。
- 首轮新测试 JSON 字符串转义导致格式/编译失败，修正测试语法后上述验证通过；未升级格式工具或跳过校验。3 个本轮 Java 文件通过语法树花括号、多行中文类型 Javadoc、无通配符导入及 Tab 检查；最终 Spotless、文档链接/围栏和 diff 检查通过；150 个生产 class 全为 major 52。


### 本轮流式可靠性与基准（2026-10-28）

- 修复前新 `ChunkBufferLifecycleTest` 运行 9 项，其中 4 项按预期失败，复现 seal 后缓冲未清空、错误未通知和 256 次重复调度；修复后全部通过。
- 真实 ARM64 Zulu JDK 8：`mvn -B -Pbenchmarks clean verify`；JDK 17：`mvn -B -Pbenchmarks clean install`。SDK 均 160 项通过（core 25、agt-core 96、Provider 39），0 失败、0 错误、0 跳过；可选 JMH 模块在两种 JDK 均构建成功。JDK 17 Spotless 通过；5 个本轮 Java 文件通过语法树花括号、多行中文类型 Javadoc、无通配符导入与 Tab 检查；文档链接/围栏、XML 嵌套和 diff 检查通过。四个生产 JAR 的 150 个 class 全为 major 52，AGT LICENSE/NOTICE 保留。
- 先完成 SDK install，再运行关联框架下述定向命令：153 项通过；示例 `mvn -B -nsu test`：13 项通过。没有调用外部 LLM。
- JMH 窗口内核前后测量使用相同 JDK 17、128 MiB 堆、2 fork、3 次 300ms 预热和 5 次 300ms 测量，65,536 字符响应/1,024 字符回调。分配约减少 32.8%；耗时均值下降但误差范围重叠，原始样本已保存在 docs/benchmarks。
- 八会话探针：任务数 8192 → 0、缓冲高水位均 69 UTF-16、线程剩余均 0；这是固定对齐回调场景，不适用于所有输入。STRICT/WINDOW SDK 基准另以四线程短跑验证入口，不作为性能定论。


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

1. 同步 SDK 与 LangChain4j 两个仓库的目标提交和交接文件，示例随 SDK 一起获取。SDK 远端为 `umuo/agt-guard-spi`，框架远端为 `umuo/langchain4j-guard`，框架使用 `codex/agent-guard-integration` 分支。迁移前仍须检查新的未提交文件，未提交变更不会随 Git 迁移。
2. 两个仓库默认放在同一父目录：`agent-guard/`、`langchain4j/`，示例位于 `agent-guard/agent-guard-example/`。从 `agt-guard-spi` clone 时可显式指定本地目录名 `agent-guard`；其他布局使用下述环境变量。路径可含空格，命令引用变量时始终加双引号。
3. 在新 Mac 安装 JDK 17、Maven 3.8+；需要验收 SDK Java 8 时再安装 JDK 8。执行 `git status --short --branch`、`git log -1 --oneline` 与 `mvn -v`，核对版本表。
4. 在 SDK 根目录执行 `mvn verify`。集成运行需要从源码重新安装本机 SNAPSHOT，不复制旧机器的 target 或整个 Maven 缓存。
5. 进入 SDK 的 `agent-guard-example/`，阅读其 README，执行以下现有脚本：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/install-local.sh
mvn -nsu test
```

脚本先安装上一级 SDK，再安装与 SDK 同级的框架模块。LangChain4j 安装步骤跳过测试、格式与 Revapi 等门禁，仅用于准备本地制品，不代表框架验证通过。其他目录布局可通过 `AGENT_GUARD_SOURCE`、`LANGCHAIN4J_SOURCE` 指定源码根目录，通过 `MAVEN_CMD` 指定 Maven 可执行文件，均为本机配置。

6. 真实 LLM 在示例目录从 `.env.example` 创建本地 `.env`，自行填入端点、模型、密钥；不把密钥写进交接文档。根据示例 README 选择 `run.sh`、`run-code-policy.sh`、`run-streaming.sh` 或 `run-plain-streaming.sh`。
7. 新会话给 Codex 的入口可以是：“先阅读 AGENTS.md 和 HANDOFF.md，核对 SDK 与 LangChain4j 仓库状态，汇报差异，再继续我指定的任务。”

### 没有远端时如何传递 Git 历史

以下仅为后续操作说明，本次没有执行。用户批准并完成需要迁移的提交后，可在每个仓库分别生成 bundle，例如 SDK：

```sh
git bundle create ../agent-guard.bundle --all
# 将 bundle 安全复制到另一台 Mac 后，在其父目录执行：
git clone -b master ./agent-guard.bundle agent-guard
```

框架仓库使用自己的名称及 `codex/agent-guard-integration` 分支；示例已内置，无需单独生成 bundle。bundle 只包含已有提交与引用，不含未提交文件或 `.env`。已有目标 checkout 时先 `git bundle verify`、检查目标工作区，再按需 fetch/合并，禁止直接重置覆盖本地改动。远端同步不在共享文档记录带凭据的 URL。

## 下一步任务

配置诊断和流式优化已提交，本轮策略易用性已交付并本地提交。下一项可进入框架适配契约与可复用验收测试；持续负载/扩大测量矩阵仍可按实际需要推进。后续范围和验收见 [PLANS.md](PLANS.md)。

## 相关文件

- [协作约定](AGENTS.md)、[长期计划](PLANS.md)、[项目入口](README.md)、[构建配置](pom.xml)。
- [配置诊断契约与用法](docs/configuration-diagnostics.md)：新增规范接口位于 `agent-guard-spec/src/main/java/org/agentguard/diagnostics/`；SDK 新测试为 `GuardDiagnosticsTest`、`AgtDiagnosticsTest`；框架修改 `AiServices`、`AiServiceContext`、`DefaultAiServices` 和 guard 包的自动配置/诊断类；示例新测试为 `example.GuardDiagnosticsTest`。
- [流式性能记录](docs/streaming-performance.md)：`agt-core` 的 ChunkBufferEngine/ChunkBufferLifecycleTest，SDK GuardStreamingTest，独立 `agent-guard-benchmarks` 模块及 docs/benchmarks 测量数据。
- [设计契约](docs/design-contract.md)、[自动装配](docs/sdk-auto-configuration.md)、[流式设计](docs/streaming-design.md)。
- [Fluent API](docs/agt-fluent-api.md)、[AGT 实现边界](docs/agt-implementation.md)、[调用链](docs/langchain4j-agt-call-chain.md)。
- 核心入口：`agent-guard-core/src/main/java/org/agentguard/core/DefaultGuardEngine.java`、`DefaultGuardEngineFactory.java`、`GuardEngineBuilder.java`（后两者同目录）。
- 策略入口：`agent-guard-agt/src/main/java/org/agentguard/agt/AgtGuards.java`、`AgtGuardProvider.java`、`AgtWindowStream.java`（同目录）。
- SPI 资源：`agent-guard-agt/src/main/resources/META-INF/services/`；测试：core/Provider/复制 AGT 模块各自的 `src/test/java/`。

## 每次收尾更新清单

更新日期、实际分支和提交；分别写清已完成、进行中、未解决；记录重要决策、下一步和相关文件；列出实际运行的命令、测试范围与结果；检查敏感内容及未提交文件。用户未授权提交时保留工作区，并明确说明新 Mac 尚不能通过 Git 获取这些文件。
