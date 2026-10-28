# 配置诊断契约

## 目标与方案

让调用方区分护栏关闭、未发现 SDK、装配成功、初始化失败及流式不兼容，并查看已注册的 Provider、策略来源类别、按执行顺序排列的阶段 Guard。

复用现有 GuardEngine/GuardProvider 和流式校验入口，不重新扫描 SPI、不读取额外配置、不执行 Guard 检查。参考 [Spring Boot ConditionEvaluationReport](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/ConditionEvaluationReport.html) 按来源报告配置结果的方式；其实现依赖 Spring BeanFactory，不适合作为零依赖 spec 的直接依赖。因此采用 JDK 8 不可变值对象和可选引擎诊断接口，保持既有自定义引擎兼容。

## 范围与语义

- spec 的 `DiagnosableGuardEngine` 是可选接口；自定义引擎未实现时报告详情不可用，不把未知误报为没有规则。
- `GuardDiagnostics` 保存引擎类型、Provider 元信息、排序后的阶段 Guard id/order、是否采用动态策略解析器、是否实现可选流式引擎接口。列表不可变，包含六阶段的空列表。
- Provider 通过默认 `policySource()` 返回 UNKNOWN；AGT 区分 PROGRAMMATIC、SYSTEM_PROPERTY、ENVIRONMENT。不暴露文件路径、策略正文、版本内容、模板变量或密钥。
- 流式接口存在只代表可以校验和开启某些模式。框架只在完整流式构建检查成功后标记当前所选模式通过，不推断 WINDOW 对任意配置可用。
- LangChain4j `AiServices.guardDiagnostics()` 返回最近构建诊断；构建前为 NOT_BUILT，状态不代表本次请求已执行全部规则。规则是否执行仍受 supports、动态策略、模式及短路影响。
- 默认关闭仍不扫描 SPI；手动引擎优先；缺少 AGT 策略仍初始化失败；工厂冲突、返回 null 和流式不兼容继续抛原异常。
- 诊断快照的生成故障只将详情标为不可用，不改变护栏配置与执行，不替换原装配异常。诊断 getter 不触发重建或重新校验。
- 默认摘要只输出状态、来源枚举、数量和布尔值，不输出任意 Provider 名、Guard id、工厂异常消息或用户数据。显式读取名称时，调用者应确保第三方元信息适合展示。

## 验收

1. 引擎诊断六阶段、顺序、空规则、动态解析器、未知第三方引擎与不可变性。
2. AGT 代码配置及真实 SPI/YAML 装配准确报告来源，摘要不泄露路径或拒绝词；缺策略行为回归。
3. 框架关闭、未发现 SDK、手动引擎、SPI、失败、工厂冲突、流式模式成功/不兼容可区分；原有阻断行为保持。
4. 诊断不调用 check/supports/resolve，不主动打开流，不重复加载 Provider。
5. SDK 真 JDK 8 与 JDK 17 验证，相关框架与示例回归，更新交接记录；本轮不提交或推送。

## SDK 使用

```java
GuardEngine engine = AgtGuards.builder().denyText("DENIED").build();
GuardDiagnostics diagnostics = GuardDiagnostics.inspect(engine);
System.out.println(diagnostics); // 默认只输出数量与布尔值
if (diagnostics.detailsAvailable()) {
    diagnostics.rulesByStage().forEach((stage, rules) -> {
        // rules 按实际执行顺序排列；读取 id() 可用于内部管理页面。
        System.out.println(stage + ": " + rules.size());
    });
    diagnostics.providers().forEach(provider -> {
        System.out.println(provider.policySource());
    });
}
```

导入 `org.agentguard.diagnostics.GuardDiagnostics`；引擎类型是 `org.agentguard.engine.GuardEngine`。这里的规则是注册到引擎的 Guard；AGT 通常为每阶段一个桥接 Guard，并不是 YAML 内部内容条目的数量。未知引擎的空列表不能解释为零规则，必须先看 detailsAvailable。

## LangChain4j 使用

```java
AiServices<Assistant> builder = AiServices.builder(Assistant.class)
        .chatModel(model)
        .guardAutoDiscovery(true);
try {
    Assistant assistant = builder.build();
    System.out.println(builder.guardDiagnostics());
    // 此后照常调用 assistant。
} catch (RuntimeException error) {
    System.out.println(builder.guardDiagnostics());
    throw error; // 不因为有诊断就忽略初始化失败
}
```

使用纯代码策略时改为 `.guardEngine(engine)`；流式模型额外设置 `.guardStreaming(options)`。`guardDiagnostics()` 是 getter，不接收开启参数，也不会开启护栏。

| 字段/状态 | 含义 |
| --- | --- |
| NOT_BUILT | 还没有执行本轮护栏配置检查 |
| DISABLED | 无引擎且未开启自动发现 |
| NO_SDK | 开启自动发现，但没有可发现的工厂；沿用现有行为继续构建 |
| CONFIGURED | 引擎已装配且当前护栏构建检查通过；不证明请求已检测或所有规则强制执行 |
| FAILED | 护栏初始化或流式配置检查失败，build 仍抛异常 |
| source | NONE / MANUAL / SPI；初始化失败时 SPI 表示本次失败发生在发现/创建路径 |
| problem | NONE / MULTIPLE_FACTORIES / SDK_INITIALIZATION / STREAMING_CONFIGURATION |
| factoryType | 选中的 SPI 工厂类型；未选中时为 null |
| engine | 引擎元信息；尚未取得引擎时为 null |
| streamingMode | 请求的 STRICT / WINDOW；未配置时为 null |
| streamingValidated | 当前引擎、流式模型、所选参数及框架返回类型的检查全部通过；同步服务不标 true |

例如纯代码同步配置的摘要形如：

```text
AgentGuardDiagnostics{status=CONFIGURED, source=MANUAL, problem=NONE, streamingMode=null, streamingValidated=false, engine=GuardDiagnostics{detailsAvailable=true, providers=1, rules=6, dynamicPolicy=false, streaming=true}}
```

`streaming=true` 表示引擎实现可选流式接口，仍需校验具体模式。快照不包含业务模型类型，因此不能用 SDK 的 streaming 字段推断框架支持 Publisher。返回值代表最近一次护栏构建检查，修改 builder 但未重新 build 时仍是上一次结果。普通模型/服务校验失败且未到达护栏检查时，可能仍为 NOT_BUILT 或上一次结果。

## 验证入口

SDK 在根目录执行 `mvn verify`；关联框架的定向测试为 `AgentGuardAutoConfigurationTest`、`AgentGuardContractTest`、`AgentGuardStreamingTest`、`AiServicesAgentGuardTest`。示例仓库执行 `mvn -nsu test`；`GuardDiagnosticsTest` 验证实际 SDK classpath 下缺策略失败与纯代码来源，现有同步/流式入口打印摘要。测试不需要真实外部 LLM 凭据。
