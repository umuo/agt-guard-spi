package org.agentguard.spi;

import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.decision.GuardDecision;
import org.agentguard.decision.GuardPolicy;

/**
 * 护栏 SPI —— 由安全 SDK（Provider）实现。
 *
 * <p>一般不直接实现本接口，而是实现对应阶段的子接口：
 * {@link AgentInputGuard} / {@link ModelInputGuard} / {@link ModelOutputGuard} /
 * {@link ToolInputGuard} / {@link ToolOutputGuard} / {@link AgentOutputGuard}。</p>
 *
 * <h3>实现约定（必须遵守）</h3>
 * <ol>
 *   <li><b>线程安全、无状态</b>：同一实例会被并发调用；跨阶段信息请放入 {@link GuardContext#state()}。</li>
 *   <li><b>不修改入参</b>：payload 不可变，需要改写时返回 {@link GuardDecision#modify}。</li>
 *   <li><b>区分拒绝与故障</b>：违规返回 {@link GuardDecision#block}；SDK 服务故障抛运行时异常，
 *       按 {@link GuardPolicy#failPolicy()} 处理。显式 GuardBlockedException 不受 FAIL_OPEN 降级。</li>
 *   <li><b>不返回 null</b>：未命中返回 {@link GuardDecision#pass()}。</li>
 *   <li><b>无业务副作用</b>：除审计日志外，不应修改外部系统状态。</li>
 *   <li><b>同步、快速</b>：在调用线程同步执行，耗时直接计入 Agent 响应时间。</li>
 * </ol>
 *
 * @param <T> 阶段 payload 类型，必须与 {@link #stage()} 的 {@link GuardStage#payloadType()} 一致
 */
public interface Guard<T> {

    /** 全局唯一 id，建议格式：{@code <provider>.<stage>.<name>}，如 {@code acme.tool-input.tenant}。 */
    String id();

    /** 所属阶段。子接口已提供默认实现。 */
    GuardStage stage();

    /**
     * 同阶段内执行顺序，越小越先执行；相同时按 id 字典序。
     * 参数改写应先于依赖这些参数的权限校验，后续规则不得使已完成的授权失效。
     */
    default int order() {
        return 0;
    }

    /**
     * 是否对当前上下文生效，用于按租户 / Agent / 工具等维度做静态过滤。
     * 返回 false 时本次跳过，不产生记录。
     */
    default boolean supports(GuardContext context) {
        return true;
    }

    /** 默认策略；平台可通过 {@link GuardPolicyResolver} 动态覆盖。 */
    default GuardPolicy defaultPolicy() {
        return GuardPolicy.DEFAULT;
    }

    /**
     * 执行检测。
     *
     * @param payload 当前 payload（可能已被同阶段前序 Guard 改写），非 null
     * @param context 上下文，{@link GuardContext#stage()} 已设置为当前阶段
     * @return 判定结果，非 null
     */
    GuardDecision<T> check(T payload, GuardContext context);
}
