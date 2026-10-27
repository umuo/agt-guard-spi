package org.agentguard.agt;

import io.agt.core.GovernanceException;
import io.agt.core.InterventionPoint;
import io.agt.core.PolicyEvaluator;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.streaming.ChunkBufferEngine;
import io.agt.core.streaming.ChunkBufferOptions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Consumer;
import org.agentguard.GuardStage;
import org.agentguard.decision.Finding;
import org.agentguard.decision.RiskCategory;
import org.agentguard.decision.RiskLevel;
import org.agentguard.exception.GuardBlockedException;
import org.agentguard.exception.GuardExecutionException;
import org.agentguard.streaming.GuardStream;
import org.agentguard.streaming.GuardStreamOptions;

/**
 * 将 AGT 字面窗口缓冲器适配为中立流会话，保持尾部保留及终态一致性校验。
 * 每个会话持有可关闭的守护调度器，不创建审批或其他治理运行时。
 */
final class AgtWindowStream implements GuardStream {
    private final ChunkBufferEngine buffer;
    private volatile RuntimeException failed;
    private volatile String matchedRule;

    AgtWindowStream(
            PolicyEvaluator evaluator,
            GuardStreamOptions options,
            Consumer<String> output,
            Consumer<Throwable> failure) {
        if (!(evaluator instanceof StructuredPolicy)) {
            throw new IllegalArgumentException(
                    "Window streaming requires StructuredPolicy; use strict mode");
        }
        List<ContentRule> rules = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ContentRule rule : ((StructuredPolicy) evaluator).contentRules()) {
            if (rule.points().contains(InterventionPoint.POST_MODEL_CALL)
                    || rule.points().contains(InterventionPoint.OUTPUT)) {
                if (!rule.replacements().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Output replacements require strict streaming");
                }
                if (!rule.denyContains().isEmpty()) {
                    rules.add(rule);
                    ids.add(rule.id());
                }
            }
        }
        if (rules.isEmpty()) {
            throw new IllegalArgumentException(
                    "Window streaming requires explicit output literal deny rules");
        }
        ChunkBufferOptions settings =
                new ChunkBufferOptions(
                        options.chunkCharacters(),
                        "，。！？\n",
                        Duration.ofMillis(options.maxWaitMillis()),
                        options.maxBufferedCharacters(),
                        ids);
        ScheduledThreadPoolExecutor scheduler =
                new ScheduledThreadPoolExecutor(
                        1,
                        task -> {
                            Thread thread = new Thread(task, "agent-guard-window");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.setRemoveOnCancelPolicy(true);
        try {
            buffer =
                    new ChunkBufferEngine(
                            settings,
                            rules,
                            scheduler,
                            true,
                            (rule, start, end, duration) -> {
                                if (rule != null) {
                                    matchedRule = rule;
                                }
                            },
                            output,
                            error -> {
                                failed = translate(error);
                                failure.accept(failed);
                            },
                            Runnable::run,
                            scheduler::shutdownNow);
        } catch (RuntimeException error) {
            scheduler.shutdownNow();
            throw error;
        }
    }

    @Override
    public void accept(String text) {
        if (failed != null) {
            return;
        }
        buffer.accept(text);
        rethrow();
    }

    @Override
    public void seal(String text) {
        try {
            buffer.seal(text);
            rethrow();
        } catch (GovernanceException error) {
            throw translate(error);
        }
    }

    @Override
    public void complete(String checkedText) {
        try {
            buffer.complete(checkedText);
            rethrow();
        } catch (GovernanceException error) {
            throw translate(error);
        }
    }

    private void rethrow() {
        if (failed != null) {
            throw failed;
        }
    }

    private RuntimeException translate(Throwable error) {
        if (error instanceof GovernanceException
                && "stream_content_denied".equals(((GovernanceException) error).code())) {
            Finding finding =
                    Finding.of(
                            matchedRule == null ? "stream_content_denied" : matchedRule,
                            RiskCategory.COMPLIANCE,
                            RiskLevel.MEDIUM);
            return new GuardBlockedException(
                    GuardStage.MODEL_OUTPUT,
                    "Streaming content rejected by safety policy",
                    java.util.Collections.singletonList(finding));
        }
        return new GuardExecutionException(
                "agt.streaming", GuardStage.MODEL_OUTPUT, "Streaming buffer failed", error);
    }

    @Override
    public void close() {
        buffer.close();
    }
}
