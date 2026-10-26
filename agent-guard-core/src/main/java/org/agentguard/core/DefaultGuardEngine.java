package org.agentguard.core;

import org.agentguard.GuardStage;
import org.agentguard.context.GuardContext;
import org.agentguard.decision.EnforcementMode;
import org.agentguard.decision.FailPolicy;
import org.agentguard.decision.Finding;
import org.agentguard.decision.GuardAction;
import org.agentguard.decision.GuardDecision;
import org.agentguard.decision.GuardPolicy;
import org.agentguard.decision.RiskCategory;
import org.agentguard.decision.RiskLevel;
import org.agentguard.engine.GuardEngine;
import org.agentguard.engine.GuardOutcome;
import org.agentguard.event.GuardEvent;
import org.agentguard.event.GuardEventListener;
import org.agentguard.event.GuardRecord;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.spi.Guard;
import org.agentguard.spi.GuardPolicyResolver;
import org.agentguard.util.Immutables;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * {@link GuardEngine} 默认参考实现。
 *
 * <h3>编排与执行语义</h3>
 * <ol>
 *   <li>按阶段索引 Guard 列表，同一阶段内按 {@link Guard#order()} 升序排列（同 order 按 id 排序）。</li>
 *   <li>策略解析后检查 {@link Guard#supports(GuardContext)}，不满足条件的直接跳过。</li>
 *   <li>通过 {@link GuardPolicyResolver} 解析动态策略（执行模式与失败策略）：
 *     <ul>
 *       <li>{@code EnforcementMode.DISABLED}：跳过不执行；</li>
 *       <li>{@code EnforcementMode.OBSERVE}（观察模式/旁路）：无论 Guard 返回什么，一律将动作降级为 {@link GuardAction#OBSERVE}，
 *           保留命中 Finding 与原始决议，不改变 payload，不阻断流程；</li>
 *       <li>{@code EnforcementMode.ENFORCE}：真实生效。</li>
 *     </ul>
 *   </li>
 *   <li>链式改写：遇到 {@code MODIFY} 时，将改写后的 payload 传递给后续 Guard。</li>
 *   <li>短路拦截：遇到首个生效的 {@code REJECT} 或 {@code BLOCK} 立即终止阶段循环，不再调用后续 Guard。</li>
 *   <li>异常容错：Guard 抛出异常时，根据 {@code FailPolicy} 决定：
 *     <ul>
 *       <li>{@code FAIL_CLOSED}：记录 {@link RiskCategory#GUARD_ERROR} 并阻断；调用结果的 payloadOrThrow 时抛出执行异常；</li>
 *       <li>{@code FAIL_OPEN}：记录 {@link RiskCategory#GUARD_ERROR} 并降级为 {@link GuardAction#OBSERVE} 放行。</li>
 *     </ul>
 *   </li>
 *   <li>审计上报：阶段执行结束（无论是放行、改写还是阻断）均会触发 {@link GuardEventListener}。</li>
 * </ol>
 */
public class DefaultGuardEngine implements GuardEngine {

    private final Map<GuardStage, List<Guard<?>>> guardsByStage;
    private final GuardPolicyResolver policyResolver;
    private final List<GuardEventListener> listeners;

    public DefaultGuardEngine(List<Guard<?>> guards,
                              GuardPolicyResolver policyResolver,
                              List<GuardEventListener> listeners) {
        this.guardsByStage = indexGuards(guards);
        this.policyResolver = policyResolver != null ? policyResolver : GuardPolicyResolver.defaults();
        this.listeners = Immutables.list(listeners);
    }

    public static GuardEngineBuilder builder() {
        return new GuardEngineBuilder();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> GuardOutcome<T> check(GuardStage stage, T payload, GuardContext context) {
        Immutables.requireNonNull(stage, "stage");
        Immutables.requireNonNull(context, "context");
        validatePayload(stage, payload);

        // 确保 context 中的 stage 为当前阶段
        GuardContext effectiveContext = context.stage() == stage ? context : context.withStage(stage);

        List<Guard<?>> stageGuards = guardsByStage.get(stage);

        long stageStart = System.nanoTime();
        T currentPayload = payload;
        String effectiveReply = null;
        GuardAction overallAction = GuardAction.PASS;
        List<Finding> allFindings = new ArrayList<Finding>();
        List<GuardRecord> records = new ArrayList<GuardRecord>(stageGuards.size());

        for (Guard<?> rawGuard : stageGuards) {
            Guard<T> guard = (Guard<T>) rawGuard;

            long guardStart = System.nanoTime();
            GuardAction decidedAction = null;
            GuardAction effectiveAction;
            List<Finding> guardFindings = Collections.emptyList();
            Throwable error = null;
            // 默认策略尚未取得时，使用 ENFORCE/FAIL_CLOSED 保护解析边界。
            GuardPolicy policy = GuardPolicy.DEFAULT;

            try {
                GuardPolicy defaultPolicy = guard.defaultPolicy();
                if (defaultPolicy == null) {
                    throw new IllegalStateException("Guard default policy must not be null");
                }
                policy = defaultPolicy;
                GuardPolicy resolved = policyResolver == GuardPolicyResolver.defaults()
                        ? defaultPolicy : policyResolver.resolve(guard, effectiveContext);
                if (resolved != null) {
                    policy = resolved;
                }
                if (policy.mode() == EnforcementMode.DISABLED || !guard.supports(effectiveContext)) {
                    continue;
                }
                GuardDecision<T> decision = guard.check(currentPayload, effectiveContext);
                if (decision == null) {
                    throw new IllegalStateException("Guard [" + guard.id() + "] returned null decision");
                }
                if (decision.action() == GuardAction.MODIFY) {
                    validatePayload(stage, decision.payload());
                }
                decidedAction = decision.action();
                guardFindings = decision.findings();

                // 3. 执行模式判断（是否处于观察模式）
                if (policy.mode() == EnforcementMode.OBSERVE) {
                    // 旁路观察模式：任何改写或拦截一律降级为 OBSERVE，payload 保持原状
                    effectiveAction = decidedAction.isHit() ? GuardAction.OBSERVE : GuardAction.PASS;
                } else {
                    effectiveAction = decidedAction;
                    if (effectiveAction == GuardAction.MODIFY && decision.payload() != null) {
                        currentPayload = decision.payload();
                    }
                    if (decision.reply() != null) {
                        effectiveReply = decision.reply();
                    }
                }
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (GuardBlockedException blocked) {
                // 兼容显式终止异常，违规不能被 FAIL_OPEN 当作检测故障放行。
                decidedAction = GuardAction.BLOCK;
                guardFindings = blocked.findings();
                effectiveAction = policy.mode() == EnforcementMode.OBSERVE
                        ? GuardAction.OBSERVE : GuardAction.BLOCK;
                if (effectiveAction == GuardAction.BLOCK) {
                    effectiveReply = blocked.reply();
                }
            } catch (RuntimeException t) {
                if (Thread.currentThread().isInterrupted()) {
                    throw t;
                }
                error = t;
                decidedAction = null;
                Finding errorFinding = Finding.builder()
                        .code("GUARD_EXECUTION_FAILURE")
                        .category(RiskCategory.GUARD_ERROR)
                        .level(RiskLevel.CRITICAL)
                        .message("安全检测服务异常")
                        .detail("Guard [" + guard.id() + "] error: " + t.getMessage())
                        .build();
                guardFindings = Collections.singletonList(errorFinding);

                // 4. 失败策略处理
                if (policy.failPolicy() == FailPolicy.FAIL_OPEN
                        || policy.mode() == EnforcementMode.OBSERVE) {
                    effectiveAction = GuardAction.OBSERVE;
                } else {
                    effectiveAction = GuardAction.BLOCK;
                }
            }

            long duration = System.nanoTime() - guardStart;
            GuardRecord record = new GuardRecord(guard.id(), stage, decidedAction,
                    effectiveAction, duration, guardFindings, error);
            records.add(record);

            if (!guardFindings.isEmpty()) {
                allFindings.addAll(guardFindings);
            }

            // 更新整体动作状态（取最高严重级别）
            overallAction = GuardAction.max(overallAction, effectiveAction);

            // 5. 短路拦截：如果是生效的 REJECT 或 BLOCK，中断后续 Guard 调用
            if (effectiveAction == GuardAction.BLOCK || effectiveAction == GuardAction.REJECT) {
                break;
            }
        }

        long totalDuration = System.nanoTime() - stageStart;

        // 6. 发布聚合审计事件
        if (!listeners.isEmpty()) {
            GuardEvent event = new GuardEvent(effectiveContext, stage, overallAction, records, allFindings, totalDuration);
            for (GuardEventListener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (RuntimeException ignored) {
                    // 审计监听器异常不得影响主流程业务
                }
            }
        }

        return new GuardOutcome<T>(stage, overallAction, currentPayload, effectiveReply, allFindings, records);
    }

    private static void validatePayload(GuardStage stage, Object payload) {
        if (!stage.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException("Payload must be a nonnull " + stage.payloadType().getName()
                    + " for stage " + stage);
        }
    }

    private static Map<GuardStage, List<Guard<?>>> indexGuards(List<Guard<?>> guards) {
        Map<GuardStage, List<Guard<?>>> map = new EnumMap<GuardStage, List<Guard<?>>>(GuardStage.class);
        for (GuardStage stage : GuardStage.values()) {
            map.put(stage, new ArrayList<Guard<?>>());
        }
        Set<String> ids = new HashSet<String>();
        if (guards != null) {
            for (Guard<?> guard : guards) {
                Immutables.requireNonNull(guard, "guard");
                Immutables.requireNonNull(guard.stage(), "guard.stage");
                String id = Immutables.requireNonNull(guard.id(), "guard.id");
                if (id.trim().isEmpty() || !ids.add(id)) {
                    throw new IllegalArgumentException("Guard id must be nonblank and unique: " + id);
                }
                map.get(guard.stage()).add(guard);
            }
        }

        // 排序：order 升序，同 order 按 id 字典序
        Comparator<Guard<?>> comparator = new Comparator<Guard<?>>() {
            @Override
            public int compare(Guard<?> g1, Guard<?> g2) {
                int c = Integer.compare(g1.order(), g2.order());
                if (c != 0) {
                    return c;
                }
                String id1 = g1.id();
                String id2 = g2.id();
                return id1.compareTo(id2);
            }
        };

        for (Map.Entry<GuardStage, List<Guard<?>>> entry : map.entrySet()) {
            Collections.sort(entry.getValue(), comparator);
        }
        return map;
    }
}
