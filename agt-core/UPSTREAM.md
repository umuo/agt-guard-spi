# 源码来源

本模块从 Agent Governance Toolkit for Java 的 `agt-core/src` 复制，来源提交为 `f672c1c`，版本为 `0.6.0-SNAPSHOT`。
项目归属及设计参考保留在 [NOTICE](NOTICE)，许可保留在 [LICENSE](LICENSE)。
原项目不是微软官方 Java SDK。

本地调整：Maven 父项目接入 Agent Guard reactor；补齐多行中文类型 Javadoc，展开通配符导入、声明注解和控制语句花括号。
随后将源码和运行时基线降为 Java 8：record 转不可变类并保留构造校验和属性映射；替换新版语法及标准库 API，增加兼容回归测试。
策略与会话编排沿用原有规则；Java 9+ 的后代进程尽力清理由 ProcessCleanup 通过可选扩展调用，Java 8 直接管理主进程。
命令清理边界见 [使用说明](../docs/agt-implementation.md)。Provider 桥接代码位于独立的 `agent-guard-agt` 模块。

构建出的 JAR 在 `META-INF/` 中包含原 LICENSE 与 NOTICE。
保留 `io.agt` 包名和制品坐标；应用应只选用本 reactor 构建的一个 agt-core，避免与另一个同坐标版本混用。
