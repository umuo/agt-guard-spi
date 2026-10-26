package org.agentguard.spi;

import org.agentguard.engine.GuardEngine;

/**
 * 安全 SDK 发布护栏引擎的启动入口，框架仅依赖本接口即可自动装配 SDK。
 *
 * <p>SDK 在 META-INF/services/org.agentguard.spi.GuardEngineFactory 中注册实现类全名。
 * 实现类须提供 public 无参构造器，构造过程应轻量，实际初始化放在 create 中。</p>
 *
 * <p>框架先发现所有工厂，再校验唯一性，不按 classpath 顺序随机选择。
 * 没有工厂表示未安装 SDK；存在多个工厂应报冲突，由应用显式提供组合引擎。</p>
 *
 * <p>SDK 自行读取并校验配置，不依赖特定 Agent 框架。
 * 引擎须线程安全，请求身份与可变状态只能通过 GuardContext 传入。
 * SDK 所有的客户端、线程池等资源由 SDK 或应用管理，框架不隐式关闭。</p>
 */
public interface GuardEngineFactory {

    /**
     * 创建已完成初始化的引擎；由框架在服务创建阶段调用，不在每次请求时调用。
     *
     * @return 非 null 的可用护栏引擎
     * @throws RuntimeException 配置缺失或初始化失败时抛出，框架必须明确报错
     */
    GuardEngine create();
}
