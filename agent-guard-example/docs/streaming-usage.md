# 流式安全护栏使用与调试

本地修改版 LangChain4j 的 TokenStream 已提供显式流式护栏入口。框架仍只依赖 spec，通过 StreamingGuardEngine 可选能力与 SDK 协作，不直接引用 AGT 的缓冲器。

## 1. 启动实际模型演示

先安装本地 SDK 与本地 LangChain4j，再填写项目根目录 .env 的模型地址、密钥和模型名：

```shell
./scripts/install-local.sh
./scripts/run-streaming.sh window
# 或完整缓存：
./scripts/run-streaming.sh strict
```

入口是 [StreamingPolicyDemo.java](../src/main/java/example/StreamingPolicyDemo.java)。它使用纯 Java 策略，不读取 YAML；默认查询 tenant-A 的 ORD-1001，经过真实模型工具调用和工具结果脱敏，逐段打印允许释放的文本。

IDE 中运行 StreamingPolicyDemo.main，第一个参数为 window 或 strict，第二个可选参数为 dotenv 文件路径。模型要支持工具调用和 OpenAI 兼容的非流式/流式协议。

本地协议测试使用实际 SSE 客户端连接本机测试服务，不消耗模型额度。尚未填入真实凭证时，不宣称已完成外部模型验证。

## 2. 构建器配置

在普通的模型、工具和可信上下文配置之外增加：

```java
.guardEngine(CodePolicyDemo.createEngine())
.guardStreaming(GuardStreamOptions.window(64, 50, 4096))
```

`GuardStreamOptions` 位于 `org.agentguard.streaming`。如果通过 SPI 安装 SDK，必须显式设置 `.guardAutoDiscovery(true)` 才会发现工厂，可以不设置 guardEngine，但仍需要显式 guardStreaming。默认只引入 SDK 不启用护栏。没有引擎却配置 guardStreaming 会构建失败。

默认不自动启用流式：仅配置流式模型和护栏时，依然要求显式选择模式，避免无意中改变安全保证。

| 配置 | 默认值 | 单位与语义 |
| --- | --- | --- |
| strict() | 缓冲上限 1048576 | UTF-16 单元，完整检查后才释放 |
| strict(limit) | 调用方指定 | 同上，限制原文和有效文本长度 |
| window() | 64、50、4096 | 新增字符触发阈值、等待毫秒数、待输出缓冲上限 |
| window(chunk, waitMillis, limit) | 调用方指定 | 字符使用 UTF-16 单元；超时只触发检查，不强制释放尾部 |

AGT 窗口另受底层词表与缓冲上限约束，包含最长模式长度后的实际缓冲必须足够，配置不足会明确失败。

## 3. 两种模式的保证

| 模式 | 释放时机 | 适用规则 |
| --- | --- | --- |
| STRICT | 本轮完整 MODEL_OUTPUT 检查后；最终轮再通过 AGENT_OUTPUT | 完整规则、输出替换、原生输出 Guardrail 重试 |
| WINDOW | 本地规则通过后释放安全前缀，扣住潜在跨片段匹配尾部 | 显式 StructuredPolicy 的有限长度字面拒绝规则 |

窗口保留最长禁止字符串长度减一的尾部，例如 DENIED 拆为 DE 和 NIED 时，DE 仍扣在缓冲中，完整命中后不会被释放。标点、阈值、计时器可触发检查，但不能把未判定尾部提前发给业务。

窗口模式不证明任意全文语义安全。完整终态检查仍会执行，若最后拒绝，已经放行的安全前缀无法撤回；需要整篇一次性通过才对外输出时选择 STRICT。

WINDOW 明确拒绝：输出替换、未知策略评估器、额外未覆盖的输出 Guard、多个流式 Provider、动态策略解析器，以及 LangChain4j 的完整输出 Guardrail 组合。输入和工具结果的脱敏仍在原六阶段执行，不受此限制。

原始模型提供完整响应时，缓冲器校验文本与已收到片段的一致性；不能先输出正文 A，再在完成事件里返回不同正文 B。STRICT 支持检查后的文本替换；WINDOW 不支持输出改写。

## 4. 执行生命周期

```text
AGENT_INPUT
    → MODEL_INPUT：合并默认模型参数后检查
    → 上游流式调用
        → partial 文本进入 GuardStream
        → STRICT 缓存 / WINDOW 检查后放行前缀
        → 思考、原始事件、工具参数预览不向业务转发
    → 完整响应 seal
    → MODEL_OUTPUT
        → 有工具：根据有效调用执行 TOOL_INPUT → 工具 → TOOL_OUTPUT，再进入下一轮
        → 无工具：原生输出 Guardrail（如有）→ AGENT_OUTPUT → complete 释放尾部 → 完成回调
```

工具的 onCompleteToolCall 不再提前触发执行。配置并发工具时，也先等待完整模型响应检查通过，再按已有执行器并行调度。工具 beforeToolExecution 回调在授权之后看到有效参数；被拒绝的工具不会触发该回调。

输出 Guardrail 的重新提示使用带护栏的模型包装器，重试不能绕过 MODEL_INPUT / MODEL_OUTPUT。STRICT 不回放之前被输出 Guardrail 拒绝的原始 token。

每轮流有独立缓冲和可关闭定时器。终止或取消后，迟到的片段与完成回调被丢弃，错误通知去重；上游提供 StreamingHandle 时尽力取消，旧式无上下文回调只能停止释放，不能假装已取消网络请求。

## 5. 回调与输出内容

业务可以继续使用 onPartialResponse 或 onPartialResponseWithContext。在上下文回调中可调用 streamingHandle.cancel()；取消会关闭缓冲并抑制后续完成事件。

模型某个中间工具轮次可能先返回一段说明文字，这段经过相应模式检查后仍可能进入 partial 回调。最终 ChatResponse 只代表最终一轮回答，不代表之前所有轮次已输出文本的拼接。Demo 用 onIntermediateResponse 划分轮次，仅对最终一轮校验增量文本与最终回答一致。

思考和原始事件没有中立检查契约，启用护栏时不暴露对应增量回调；完整响应中的 thinking 字段也会移除。模型、工具及业务的内部追踪元数据不是最终业务文本，不应直接整体序列化给终端用户。

## 6. 当前边界

- 当前支持 TokenStream；Flow.Publisher 及 PublisherAdapter 的独立异步状态机组合在构建时明确拒绝。可在业务层将 TokenStream 安全回调接到自己的 SSE 输出。
- 多模态和任意未知内容类型保持明确拒绝，不通过仅抽取文字降级检查。
- 并行工具不提供整批原子性。一个工具拒绝不会回滚其他已开始的工具。
- 窗口为本地同步字面规则，不把远程检测无限排队；上游已聚合的完整模型响应内存由模型客户端自身负责。
- 启用流式不代表启用了 AGT 的会话审批、预算、数据访问等运行时能力。

## 7. 测试和断点

```shell
mvn -nsu test
```

[StreamingPolicyDemoTest.java](../src/test/java/example/StreamingPolicyDemoTest.java) 通过真实 OpenAI SSE 客户端验证：两轮模型工具循环、输入和工具结果脱敏、多个已检查输出批次、跨片段拒绝，以及 STRICT 最终拒绝时零释放。

源码调试顺序：

1. LangChain4j 的 DefaultAiServices.validate：检查显式模式与引擎能力。
2. AiServiceStreamingResponseHandler 构造：检查 MODEL_INPUT 并建立本轮缓冲。
3. AgentGuardStreamingSupport.accept：片段进入中立 GuardStream。
4. SDK 的 AgtWindowStream / ChunkBufferEngine：检查和保留尾部。
5. AgentGuardStreamingSupport.checkResponse：seal 和完整 MODEL_OUTPUT。
6. ToolService：有效工具的输入授权与结果检查。
7. checkAgentOutput / complete：最终检查和尾部释放。

相关文档：[普通代码策略案例](code-policy-demo.md)、[调用链](langchain4j-agt-call-chain.md)、[新框架接入指南](agent-framework-integration-guide.md)。
