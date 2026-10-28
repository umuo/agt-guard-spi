# 流式可靠性与性能基准

## 本轮范围与验收

复用现有 ChunkBufferEngine 与 GuardStream，不改变字面匹配规则或 STRICT/WINDOW 的安全边界。先复现 seal 检查失败后的资源残留和单次大回调反复调度定时器，再以测试约束修复。

验收：seal 的拒绝或审计故障立即关闭、清空缓冲、取消定时器并且错误回调至多一次；每次 accept 最多为剩余未检查内容创建一次定时任务；旧任务、关闭后的迟到输入不得释放内容；跨片段与 Unicode 回归通过；提供带预热和 fork 的可执行基准、原始结果和可复现命令。性能指标不进入单元测试的固定耗时阈值。

## 覆盖盘点

- `ChunkBufferEngineTest`：禁止词全部切分位置与超时、Unicode 配对、大小回调、并发终止、审计失败、容量和词表约束。
- `GuardStreamingTest`：SDK 窗口尾部、完整缓冲、改写、配置组合拒绝、终态正文一致性。
- LangChain4j `AgentGuardStreamingTest`：最终输出、模型阻断、延后工具副作用、输入阻断、容量、输出 Guardrail 重试、取消、并行工具和直接工具返回。
- 本轮补充 `ChunkBufferLifecycleTest`：seal 故障终止、定时器创建次数与旧任务、回调重入/异常和关闭；SDK 补充实际定时器线程释放验证。

## 工具选择

采用 [OpenJDK JMH](https://github.com/openjdk/jmh) 的预热、独立 fork、并发参数与 GC profiler，而非将一次 nanoTime 差值当作稳定性能结论。JMH 仅放入可选 benchmarks profile 的独立模块，不进入 SDK 生产依赖；运行指标探针复用现有 Audit 和缓冲状态入口，不新增生产观测接口。

短基准只用于验证入口和提供本机前后对照；跨 Mac 必须使用相同输入、JDK、参数，并保留置信区间。真实 LLM 网络延迟不在这轮基准内。

## 构建与运行

要求 JDK 17（SDK 与可选模块另有真实 JDK 8 构建验收）；基准依赖 JMH 1.37。默认 SDK reactor 仍是四模块，仅显式 profile 才包含基准：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -Pbenchmarks clean verify
java -jar agent-guard-benchmarks/target/streaming-benchmarks.jar -l
# 默认使用 2 个 fork、3 次预热与 5 次测量，每次 1 秒；可用 -t 4 测并发
java -jar agent-guard-benchmarks/target/streaming-benchmarks.jar StreamingBenchmark -prof gc
# 较短的单场景对照，结果保留为 JMH JSON
java -jar agent-guard-benchmarks/target/streaming-benchmarks.jar StreamingBenchmark.windowKernel \
    -p characters=65536 -p callbackCharacters=1024 \
    -wi 3 -w 300ms -i 5 -r 300ms -f 2 -t 1 \
    -jvmArgs '-Xms128m -Xmx128m' -prof gc -rf json -rff target/streaming-result.json
# 独立并发观测：响应长度、回调长度、会话数
java -cp agent-guard-benchmarks/target/streaming-benchmarks.jar \
    org.agentguard.benchmarks.StreamingProbe 65536 1024 8
```

- `windowKernel` 使用每个 JMH 线程复用的真实调度器，测一条安全响应从分片接收到正文校验、尾部释放的窗口内核成本。
- `strictSdk` / `windowSdk` 每条响应打开真实 SDK 流、执行 MODEL_OUTPUT/AGENT_OUTPUT 并完成释放，包含实际 SDK 会话创建与关闭成本。引擎及输入分片在 Trial setup 中准备，不在每次操作重建策略。
- 支持 4,096 / 65,536 字符响应和 16 / 1,024 字符回调。一个 op 是一整条响应，不是一个 token。
- `-prof gc` 给出分配量，不是峰值存活内存。默认不调用真实 LLM，不接入任意语义检测服务。
- 探针仅观测窗口内核，每会话独立拥有真实调度器；首段延迟从开始喂入分片到输出回调，检查耗时来自现有 Audit，缓冲高水位是待处理 UTF-16 长度而非 JVM 堆大小。所有线程实际退出后才报告结果。

## 本机前后对照（2026-10-28）

基线 SDK 为 `9f79592`；候选为该提交之后本轮 `ChunkBufferEngine` 修复，具体版本为包含本记录的流式优化提交。两者使用相同 JMH harness、JDK 17 编译/运行、128 MiB 固定堆；环境为 macOS 15.5、ARM64、Zulu 17.0.11。输入为重复 safe 文本，拒绝词 SECRET，检查阈值 64，尾部重叠 5，缓冲上限 4,096，等待时间 50 ms。顺序运行前后测试，测量期间没有其他构建任务。

| JMH 窗口内核（10 个测量样本） | 修改前 | 修改后 |
| --- | ---: | ---: |
| 平均每条响应耗时，us/op | 6508.191 ± 2290.456 | 4063.011 ± 276.743 |
| 平均分配，B/op | 433987.252 | 291457.928 |

分配量约减少 32.8%；耗时均值约减少 37.6%，但短测的误差范围有重叠，不能据此承诺稳定百分比或生产 SLA。更长测量、更多 fork、其他输入与机器可能得到不同结果。小回调仍需要超时检查，不能把本场景的零定时任务推广到所有流式输入。

同一探针对两个 JAR 的八会话观测：

| 观测项 | 修改前 | 修改后 |
| --- | ---: | ---: |
| 创建的定时任务总数 | 8192 | 0 |
| 峰值实际定时器线程 | 8 | 0 |
| 最大待处理 UTF-16 单元 | 69 | 69 |
| 结束后实际定时器线程 | 0 | 0 |
| 平均首段释放，us | 5545.188 | 2250.739 |
| Audit 平均扫描耗时，us | 2.271 | 5.043 |

探针的延迟是单次冷启动并发观测，无预热或置信区间，不据此推断首段或单次扫描的稳定改善。确定性单元测试另行约束任务数、缓冲清空、错误次数和旧任务忽略；测试不使用这些耗时阈值。

JMH 原始测量样本：[修改前](benchmarks/streaming-window-before.json)、[修改后](benchmarks/streaming-window-after.json)；并发探针 CSV：[修改前](benchmarks/streaming-probe-before.csv)、[修改后](benchmarks/streaming-probe-after.csv)。JMH JSON 仅将本机 JDK 可执行文件绝对路径替换成占位符，其他测量字段保持原值。

复现前后对照时，在独立 checkout 中检出基线提交，将相同的 `agent-guard-benchmarks` 源码与 POM 放入该 checkout；先顺序安装基线四个模块，再直接通过 `mvn -f agent-guard-benchmarks/pom.xml package` 构建同一 harness，复制 JAR 到构建目录之外。然后构建候选并复制另一个 JAR，使用同一 Java 可执行文件和上述参数分别运行。不要在两次测量中更换规则、回调大小、堆或编译环境，也不要一边安装 SNAPSHOT 一边编译依赖它的仓库。

## 修复语义

seal 的正文不一致、尾部拒绝或 Audit 异常统一走 fail：先关闭、清空并取消任务，再通知一次错误，保留抛出异常。complete 中再次捕获同一错误时不会重复通知。

accept 内的阈值与标点检查继续同步执行；只对回调末尾残留的未检查内容安排一次定时任务。已有期限不延后，新任务从首个未检查单元开始计时并扣除已处理时间。关闭、取消与任务世代校验继续阻止旧回调释放内容。完整回调若已检查完全部文本，无需启动定时器线程。

本轮不增加共享调度器、不承诺硬实时超时、不扩展 WINDOW 可接受的策略，也不支持 Flow.Publisher 或远程增量检测。
