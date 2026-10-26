package org.agentguard.event;

/**
 * 审计事件监听器 SPI。用于对接日志输出、指标收集 (Metrics)、OpenTelemetry 分布式追踪或安全上报中心。
 */
public interface GuardEventListener {

    /**
     * 当一个阶段的检测完成并生成聚合事件时触发。
     * 建议实现中保持轻量、异步或非阻塞，避免拖慢主流程。
     *
     * @param event 聚合审计事件
     */
    void onEvent(GuardEvent event);
}
