package io.agt.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agt.core.approval.ApprovalRequest;
import io.agt.core.approval.ApprovalResponse;
import io.agt.core.audit.AuditRecord;
import io.agt.core.policy.ContentRule;
import io.agt.core.policy.ParameterRule;
import io.agt.core.policy.StructuredPolicy;
import io.agt.core.policy.ToolRule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 异步治理特性的单元测试。
 *
 * <p>验证异步调用的上下文传递、取消传播、超时到期、并发限制与资源安全释放。
 */
class AsyncGovernanceTest {
    static final TrustedContext CTX = new TrustedContext("agent", "tenant", "user", Java8.set());

    static AgentControl runtime() {
        return AgentControl.builder(StructuredPolicy.builder("v2").build()).audit(r -> {}).build();
    }

    static String code(CompletableFuture<?> f) {
        Throwable e = assertThrows(Exception.class, () -> f.get(3, TimeUnit.SECONDS));
        while (e.getCause() != null
                && (e instanceof ExecutionException || e instanceof CompletionException)) {
            e = e.getCause();
        }
        return e instanceof GovernanceException
                ? ((GovernanceException) e).code()
                : e.getClass().getSimpleName();
    }

    @Test
    void callbacksRestoreSessionAndAvoidProviderThread() throws Exception {
        try (AgentControl c = runtime();
                HostSession s = c.openSession(CTX)) {
            CompletableFuture<String> source = new CompletableFuture<String>();
            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<String> result =
                    s.runAsync(
                            "in",
                            v -> {
                                assertSame(s, c.currentSession());
                                started.countDown();
                                return source;
                            },
                            v -> v,
                            (v, e) -> (String) e);
            CompletableFuture<String> callback =
                    result.thenApply(
                            v -> {
                                assertSame(s, c.currentSession());
                                assertEquals("agt-worker", Thread.currentThread().getName());
                                return v;
                            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread provider = new Thread(() -> source.complete("ok"), "provider-io");
            provider.start();
            provider.join();
            assertEquals("ok", callback.get(3, TimeUnit.SECONDS));
            assertFalse(s.isAsyncRun());
            assertTrue(
                    c.workers
                            .submit(
                                    () -> {
                                        try {
                                            c.currentSession();
                                            return false;
                                        } catch (GovernanceException e) {
                                            return e.code().equals("missing_session");
                                        }
                                    })
                            .get());
        }
    }

    @Test
    void cancellationPropagatesAndStopsSession() throws Exception {
        try (AgentControl c = runtime();
                HostSession s = c.openSession(CTX)) {
            CompletableFuture<String> source = new CompletableFuture<String>();
            CountDownLatch ready = new CountDownLatch(1);
            CompletableFuture<String> result =
                    c.runAsync(
                            "in",
                            v -> {
                                ready.countDown();
                                return source;
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
            assertEquals("operation_cancelled", s.failureCode());
            assertThrows(GovernanceException.class, s::checkActive);
        }
    }

    @Test
    void closeCancelsPendingResultAndSource() throws Exception {
        try (AgentControl c = runtime()) {
            HostSession s = c.openSession(CTX);
            CompletableFuture<String> source = new CompletableFuture<String>();
            CountDownLatch ready = new CountDownLatch(1);
            CompletableFuture<String> result =
                    c.runAsync(
                            "in",
                            v -> {
                                ready.countDown();
                                return source;
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            s.close();
            assertEquals("session_closed", code(result));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void expiryEndsNeverCompletingSource() throws Exception {
        try (AgentControl c =
                        AgentControl.builder(StructuredPolicy.builder("v2").build())
                                .budget(new BudgetLimits(2, 2, 500))
                                .audit(r -> {})
                                .build();
                HostSession s = c.openSession(CTX)) {
            CompletableFuture<String> source = new CompletableFuture<String>();
            CountDownLatch started = new CountDownLatch(1);
            CompletableFuture<String> result =
                    c.runAsync(
                            "in",
                            v -> {
                                started.countDown();
                                return source;
                            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("session_expired", code(result));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void runtimeCloseAndRevokeResolvePendingFutures() throws Exception {
        for (boolean close : Java8.list(true, false)) {
            AgentControl c = runtime();
            HostSession s = c.openSession(CTX);
            CompletableFuture<String> source = new CompletableFuture<String>();
            CountDownLatch ready = new CountDownLatch(1);
            CompletableFuture<String> result =
                    c.runAsync(
                            "x",
                            v -> {
                                ready.countDown();
                                return source;
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            if (close) {
                c.close();
            } else {
                s.revoke();
            }
            assertEquals(close ? "runtime_closed" : "session_revoked", code(result));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
            s.close();
            c.close();
        }
    }

    @Test
    void overlappingTopLevelCallsRefused() throws Exception {
        try (AgentControl c = runtime();
                HostSession s = c.openSession(CTX)) {
            CompletableFuture<String> source = new CompletableFuture<String>();
            CountDownLatch ready = new CountDownLatch(1);
            CompletableFuture<String> first =
                    c.runAsync(
                            "x",
                            v -> {
                                ready.countDown();
                                return source;
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertEquals(
                    "concurrent_run_unsupported",
                    code(c.runAsync("y", v -> CompletableFuture.completedFuture("bad"))));
            assertTrue(first.isCompletedExceptionally());
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void contextualExecutorCleansReusedThreads() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (AgentControl c = runtime();
                HostSession s = c.openSession(CTX)) {
            CompletableFuture<String> done = new CompletableFuture<String>();
            c.contextualExecutor(pool)
                    .execute(() -> done.complete(c.currentSession().context().tenantId()));
            assertEquals("tenant", done.get(2, TimeUnit.SECONDS));
            assertEquals(
                    "missing_session",
                    pool.submit(
                                    () -> {
                                        try {
                                            c.currentSession();
                                            return "leaked";
                                        } catch (GovernanceException e) {
                                            return e.code();
                                        }
                                    })
                            .get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void capacityRejectedAndCanceledQueueSlotReclaimed() throws Exception {
        try (AgentControl c =
                AgentControl.builder(StructuredPolicy.builder("v2").build())
                        .asyncLimits(new AsyncLimits(1, 1))
                        .audit(r -> {})
                        .build()) {
            HostSession s1 = c.openSession(CTX);
            CountDownLatch gate = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            CompletableFuture<String> f1 =
                    s1.runAsync(
                            "x",
                            v -> {
                                entered.countDown();
                                try {
                                    gate.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return CompletableFuture.completedFuture("a");
                            },
                            v -> v,
                            (v, e) -> (String) e);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            HostSession s2 = c.openSession(CTX);
            CompletableFuture<String> f2 =
                    c.runAsync("y", v -> CompletableFuture.completedFuture("b"));
            HostSession s3 = c.openSession(CTX);
            assertEquals(
                    "async_capacity_exceeded",
                    code(c.runAsync("z", v -> CompletableFuture.completedFuture("c"))));
            assertTrue(f2.cancel(true));
            assertEquals(0, c.workers.getQueue().size());
            gate.countDown();
            assertEquals("a", f1.get(3, TimeUnit.SECONDS));
            s3.close();
            s2.close();
            s1.close();
        }
    }

    @Test
    void asyncApprovalRemainsBoundToEffectiveAction() throws Exception {
        ParameterRule params =
                ParameterRule.object(Java8.map("query", ParameterRule.string(null)), "query");
        StructuredPolicy p =
                StructuredPolicy.builder("v2")
                        .tool("lookup", new ToolRule(Java8.set(), params, true))
                        .content(
                                new ContentRule(
                                        "rewrite",
                                        Java8.set(InterventionPoint.PRE_TOOL_CALL),
                                        Java8.list(),
                                        Java8.map("old", "new")))
                        .build();
        CompletableFuture<ApprovalRequest> requested = new CompletableFuture<ApprovalRequest>();
        CompletableFuture<ApprovalResponse> approved = new CompletableFuture<ApprovalResponse>();
        AtomicInteger effects = new AtomicInteger();
        try (AgentControl c =
                        AgentControl.builder(p)
                                .audit(r -> {})
                                .approvalTimeout(Duration.ofSeconds(2))
                                .approvalResolver(
                                        r -> {
                                            requested.complete(r);
                                            return approved;
                                        })
                                .build();
                HostSession s = c.openSession(CTX)) {
            CompletableFuture<String> result =
                    s.operationAsyncPrepared(
                            true,
                            "lookup",
                            Java8.map("query", "old"),
                            v -> v,
                            v -> {
                                effects.incrementAndGet();
                                assertEquals(Java8.map("query", "new"), v);
                                return CompletableFuture.completedFuture("ok");
                            },
                            v -> v,
                            (v, e) -> (String) e);
            ApprovalRequest request = requested.get(2, TimeUnit.SECONDS);
            assertEquals(Java8.map("query", "new"), request.action().target());
            assertEquals(0, effects.get());
            approved.complete(ApprovalResponse.allow(request));
            assertEquals("ok", result.get(3, TimeUnit.SECONDS));
            assertEquals(1, effects.get());
            assertEquals(1, s.budgetSnapshot().toolCalls());
        }
    }

    @Test
    void deadlineCanFinishWhileSuccessAuditIsBlocked() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AgentControl c =
                        AgentControl.builder(StructuredPolicy.builder("v2").build())
                                .budget(new BudgetLimits(2, 2, 500))
                                .audit(
                                        r -> {
                                            if (r.verdict().equals("EXECUTION")
                                                    && r.executionOutcome().equals("succeeded")) {
                                                blocked.countDown();
                                                release.await(3, TimeUnit.SECONDS);
                                            }
                                        })
                                .build();
                HostSession session = c.openSession(CTX)) {
            try {
                CompletableFuture<String> result =
                        c.runAsync("x", v -> CompletableFuture.completedFuture("y"));
                assertTrue(blocked.await(2, TimeUnit.SECONDS));
                assertEquals("session_expired", code(result));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void queuedContextFutureFailsAfterCloseInsteadOfHanging() throws Exception {
        ExecutorService host = Executors.newSingleThreadExecutor();
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AgentControl c = runtime()) {
            host.submit(
                    () -> {
                        occupied.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            HostSession session = c.openSession(CTX);
            CompletableFuture<String> result =
                    CompletableFuture.supplyAsync(
                            () -> c.currentSession().sessionId(), c.contextualExecutor(host));
            session.close();
            release.countDown();
            assertEquals("session_closed", code(result));
        } finally {
            release.countDown();
            host.shutdownNow();
        }
    }

    @Test
    void ownedAsyncSessionClosesWithoutBlockingCallerOrLeakingBinding() throws Exception {
        List<AuditRecord> events = Collections.synchronizedList(new ArrayList<AuditRecord>());
        try (AgentControl c =
                AgentControl.builder(StructuredPolicy.builder("v2").build())
                        .audit(events::add)
                        .build()) {
            CountDownLatch ready = new CountDownLatch(1);
            CompletableFuture<String> source = new CompletableFuture<String>();
            AtomicReference<HostSession> captured = new AtomicReference<HostSession>();
            CompletableFuture<String> result =
                    c.withSessionAsync(
                            CTX,
                            () -> {
                                captured.set(c.currentSession());
                                return c.runAsync(
                                        "x",
                                        v -> {
                                            ready.countDown();
                                            return source;
                                        });
                            });
            assertEquals(
                    "missing_session",
                    assertThrows(GovernanceException.class, c::currentSession).code());
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            source.complete("ok");
            assertEquals("ok", result.get(3, TimeUnit.SECONDS));
            assertTrue(captured.get().budgetSnapshot().closed());
            assertEquals(
                    1,
                    events.stream()
                            .filter(r -> r.point() == InterventionPoint.AGENT_SHUTDOWN)
                            .count());
        }
    }

    @Test
    void ownedAsyncCancellationClosesSession() throws Exception {
        try (AgentControl c = runtime()) {
            CountDownLatch ready = new CountDownLatch(1);
            CompletableFuture<String> source = new CompletableFuture<String>();
            AtomicReference<HostSession> captured = new AtomicReference<HostSession>();
            CompletableFuture<String> result =
                    c.withSessionAsync(
                            CTX,
                            () -> {
                                captured.set(c.currentSession());
                                return c.runAsync(
                                        "x",
                                        v -> {
                                            ready.countDown();
                                            return source;
                                        });
                            });
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            assertTrue(result.cancel(true));
            assertThrows(CancellationException.class, () -> source.get(2, TimeUnit.SECONDS));
            assertTrue(captured.get().budgetSnapshot().closed());
        }
    }

    @Test
    void terminationReleasesReservationsBeforeNotifyingButKeepsStartedCounts() {
        try (AgentControl control = runtime();
                HostSession session = control.openSession(CTX)) {
            session.reserve(false);
            session.commit(false);
            session.reserve(false);
            session.reserve(true);
            CompletableFuture<String> result =
                    session.watchTermination(new CompletableFuture<String>());
            AtomicReference<HostSession.BudgetSnapshot> observed =
                    new AtomicReference<HostSession.BudgetSnapshot>();
            result.whenComplete((value, error) -> observed.set(session.budgetSnapshot()));
            session.refuse("test_terminated");
            assertEquals(1, observed.get().modelCalls());
            assertEquals(0, observed.get().toolCalls());
            session.release(false);
            session.release(true);
            assertEquals(1, session.budgetSnapshot().modelCalls());
            assertEquals(0, session.budgetSnapshot().toolCalls());
        }
    }
}
