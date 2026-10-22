package com.risk.gateway.engine;

import com.risk.gateway.Models.*;
import groovy.lang.Script;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ScriptRunnerTest {
    public static class Hit extends Script { public Object run() { return "ROUTE_A"; } }
    public static class Invalid extends Script { public Object run() { return 42; } }
    public static class Broken extends Script { public Object run() { throw new IllegalStateException(); } }
    public static class Miss extends Script { public Object run() { return null; } }
    public static class Slow extends Script {
        public Object run() {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        }
    }
    private CompiledRule rule(Class<? extends Script> type, int timeout) {
        return new CompiledRule(new Rule("TEST", "测试", 10, "", null, "ROUTE", timeout), type, "digest");
    }

    @Test
    void parsesHitMissInvalidAndErrors() {
        // 直接执行器仅供功能测试，生产必须使用独立有界线程池。
        ScriptRunner runner = new ScriptRunner(Runnable::run);
        long budget = TimeUnit.SECONDS.toNanos(1);
        assertEquals(Book.A_BOOK, runner.execute(rule(Hit.class, 1000), Map.of(), budget).result().value());
        assertNull(runner.execute(rule(Miss.class, 1000), Map.of(), budget).error());
        assertEquals("INVALID_RETURN_VALUE", runner.execute(rule(Invalid.class, 1000), Map.of(), budget).error());
        assertEquals("SCRIPT_ERROR", runner.execute(rule(Broken.class, 1000), Map.of(), budget).error());
    }

    @Test
    void handlesTimeoutAndRejection() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            ScriptRunner runner = new ScriptRunner(pool);
            assertEquals("SCRIPT_TIMEOUT", runner.execute(rule(Slow.class, 10), Map.of(), TimeUnit.SECONDS.toNanos(1)).error());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));
        }
        ScriptRunner rejected = new ScriptRunner(task -> { throw new RejectedExecutionException(); });
        assertEquals("EXECUTOR_OVERLOADED", rejected.execute(rule(Hit.class, 1000), Map.of(), TimeUnit.SECONDS.toNanos(1)).error());
        assertEquals("BUDGET_EXCEEDED", rejected.execute(rule(Hit.class, 1000), Map.of(), 0).error());
    }

    @Test
    void compiledInfiniteLoopIsCooperativelyInterruptedAndWorkerRecovers() throws Exception {
        var compiler = new GroovyRuleCompiler(new ScriptGuard(),
            new com.risk.gateway.config.RuleConfiguration.Properties(30,100,100,32768,100,120));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var loop = compiler.compile(new Rule("LOOP","循环",1,"while (true) { }; return null",null,"ROUTE",100));
            var hit = compiler.compile(new Rule("HIT","命中",1,"return 'ROUTE_A'",null,"ROUTE",1000));
            ScriptRunner runner = new ScriptRunner(pool);
            assertEquals("SCRIPT_TIMEOUT", runner.execute(loop,Map.of(),TimeUnit.SECONDS.toNanos(1)).error());
            assertEquals(Book.A_BOOK, runner.execute(hit,Map.of(),TimeUnit.SECONDS.toNanos(1)).result().value());
        } finally {
            compiler.close();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));
        }
    }

    @Test
    void preservesCallerInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertEquals("INTERRUPTED", new ScriptRunner(Runnable::run).execute(rule(Hit.class, 1000), Map.of(), 1000).error());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
