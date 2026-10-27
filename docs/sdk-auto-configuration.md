# SDK 引入后自动生效

LangChain4j 的生产代码只依赖 `agent-guard-spec`。SDK 发布工厂的 Java SPI 注册文件后，应用在构建 AI Service 时就会发现并启用引擎，无需调用 `.guardEngine(...)`。

```text
业务应用
├── LangChain4j → agent-guard-spec
└── 安全 SDK → agent-guard-core → agent-guard-spec
```

这里的 LangChain4j 指本项目已接入六阶段护栏的版本，未经适配的其他发行版不会凭空获得检查点。非流式文本与工具调用沿用原契约；TokenStream 可显式开启流式护栏，模式及限制见 [流式契约](streaming-design.md)。

## SDK 使用参考引擎

SDK 的 Maven 项目依赖：

```xml
<dependency>
    <groupId>org.agentguard</groupId>
    <artifactId>agent-guard-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

实现 `GuardProvider`，由它返回具体 SDK 的检测规则。下面是一个可编译的接入示例，仅通过约定标记验证自动装配，不代表真实的安全检测算法：

```java
package com.example.security;

import org.agentguard.context.GuardContext;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardDecision;
import org.agentguard.decision.RiskCategory;
import org.agentguard.decision.RiskLevel;
import org.agentguard.model.AgentInput;
import org.agentguard.spi.AgentInputGuard;
import org.agentguard.spi.Guard;
import org.agentguard.spi.GuardProvider;

import java.util.Collection;
import java.util.Collections;

/**
 * 发布 SDK 的检测规则，实际项目应在各阶段规则内调用具体安全 SDK。
 */
public final class SampleSdkGuardProvider implements GuardProvider {

    private final Collection<? extends Guard<?>> guards =
            Collections.<Guard<?>>singletonList(new DemoInputGuard());

    @Override
    public String name() {
        return "sample-security-sdk";
    }

    @Override
    public Collection<? extends Guard<?>> guards() {
        return guards;
    }

    /**
     * 仅用于验证装配链路的示例规则，不提供实际 Prompt 注入防护。
     */
    private static final class DemoInputGuard implements AgentInputGuard {

        @Override
        public String id() {
            return "sample.agent-input.demo";
        }

        @Override
        public GuardDecision<AgentInput> check(AgentInput input, GuardContext context) {
            if (input.text().contains("[blocked-demo]")) {
                Finding finding = Finding.of("DEMO_DENIED", RiskCategory.OTHER, RiskLevel.HIGH);
                return GuardDecision.block("示例规则拒绝请求", finding);
            }
            return GuardDecision.pass();
        }
    }
}
```

在 SDK 的 `src/main/resources/META-INF/services/` 下添加两个文件，文件名没有扩展名。

`org.agentguard.spi.GuardEngineFactory` 内容：

```text
org.agentguard.core.DefaultGuardEngineFactory
```

`org.agentguard.spi.GuardProvider` 内容：

```text
com.example.security.SampleSdkGuardProvider
```

构建 SDK JAR 后，可用 `jar tf` 检查这两个描述文件是否存在。使用 shaded/uber JAR 时需要合并服务描述文件，不能让打包插件覆盖其他 Provider 注册。

业务项目引入 SDK 和适配过的 LangChain4j，照常调用：

```java
Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(model)
        .build();
```

无需显式构造引擎。示例 Provider 在输入包含 `[blocked-demo]` 时应抛 `GuardBlockedException`，且不会调用模型。真实 SDK 需要的密钥、服务地址等配置由 SDK 自行读取和校验；缺失配置应使初始化失败。

## SDK 使用自有引擎或配置策略

实现规范层的 `GuardEngineFactory.create()`，返回已经配置好的 `GuardEngine`，并将该工厂注册到 `org.agentguard.spi.GuardEngineFactory` 描述文件。
SDK 可以在工厂内部配置策略、审计、远程客户端或组合多个 Provider；也可以完全不依赖 core，返回自己的引擎实现。
框架无需因此修改依赖。SDK 的工厂应具备 public 无参构造器，构造器保持轻量；实际初始化放在 `create()`。

## 默认行为与配置

| 配置或场景 | 行为 |
| --- | --- |
| 默认 | 每个 AI Service 构建上下文自动发现工厂 |
| 没有工厂 | 不启用护栏，保持原行为 |
| 一个有效工厂 | 在 build 时初始化并接入六阶段 |
| 多个不同工厂 | 构建失败，不随机选择；应用可显式提供组合引擎 |
| 工厂注册损坏、初始化失败或返回 null | 构建失败，保留异常原因 |
| 默认工厂未加载到任何 Guard | 构建失败，避免空引擎被当成有效 SDK |
| `.guardEngine(engine)` | 显式引擎优先，不扫描自动工厂 |
| `.guardAutoDiscovery(false)` | 关闭扫描；已有或显式配置的引擎仍生效 |
| `.guardEngine(null)` | 清除当前引擎；自动发现开启时下一次 build 会重新发现 |
| 自动启用后配置流式模型 | 仍需显式 guardStreaming；缺少能力或模式不兼容则失败 |

发现使用 LangChain4j 已有的 `ServiceHelper`，优先使用线程上下文类加载器，未找到工厂时回退到框架类加载器。不会将工厂或引擎保存到全局静态缓存。

工厂在服务构建阶段初始化一次；服务请求复用引擎，每次请求仍创建独立 GuardContext。一个构建器再次 build 时复用其已初始化引擎。不同服务上下文分别初始化，不假设进程中只有一个 SDK 配置。

SDK 创建的客户端和线程池由 SDK 或应用管理；工厂接口不意味着框架会自动关闭资源。业务身份仍通过可信 InvocationParameters 或 `.guardContextFactory(...)` 注入，不从模型参数推导。

只引入 spec/core，或者只注册 GuardProvider，不等于安装了可自动启用的 SDK。必须发布工厂描述文件。core 故意不内置自动注册，防止其作为工具依赖出现时改变应用行为。
