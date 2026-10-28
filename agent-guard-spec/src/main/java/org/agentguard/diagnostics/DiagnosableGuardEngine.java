package org.agentguard.diagnostics;

import org.agentguard.engine.GuardEngine;

/**
 * 可选的引擎配置诊断能力，不执行请求检查或改变运行状态。
 * 未实现此接口的第三方引擎仍可正常接入框架。
 */
public interface DiagnosableGuardEngine extends GuardEngine {
    GuardDiagnostics diagnostics();
}
