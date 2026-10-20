package com.risk.gateway.logging;

import com.risk.gateway.Models.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DecisionLoggerTest {
    private DecisionContext context() {
        var order = new OrderRequest("evt", "ORDER_SUBMIT", 1L, 2L, "ord", "EURUSD", new BigDecimal("1.00"),
            "BUY", new BigDecimal("1.08"), "MARKET", 1L);
        var decision = new Decision(Book.B_BOOK, "default", List.of());
        var response = new ResponseData("evt", "ord", Book.B_BOOK, "ALLOW", "L0", "default", List.of(), "adp",
            1.2, 0.2, false, null, 1791547200000L);
        return new DecisionContext(order, "trace", Map.of(), new Evaluation(List.of(), 0.2, false), decision, response);
    }
    @Test
    void retriesWithSameKeyAndDrainsOnStop() {
        var registry = new SimpleMeterRegistry();
        var attempts = new AtomicInteger();
        var keys = new CopyOnWriteArrayList<String>();
        DecisionLogSink sink = (key, context) -> {
            keys.add(key);
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary");
        };
        var logger = new DecisionLogger(sink, registry, 10, 3, 2000);
        logger.start();
        assertTrue(logger.logAsync(context()));
        logger.stop();
        assertEquals(2, keys.size());
        assertEquals(keys.get(0), keys.get(1));
        assertEquals(1.0, registry.counter("risk.decision.log.written").count());
        assertFalse(logger.logAsync(context()));
    }
    @Test
    void queueOverflowIsObservableWithoutBlockingCaller() throws Exception {
        var entered = new CountDownLatch(1);
        var unblock = new CountDownLatch(1);
        var registry = new SimpleMeterRegistry();
        DecisionLogSink sink = (key, context) -> {
            entered.countDown();
            try { unblock.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        var logger = new DecisionLogger(sink, registry, 1, 1, 2000);
        logger.start();
        try {
            assertTrue(logger.logAsync(context()));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(logger.logAsync(context()));
            assertFalse(logger.logAsync(context()));
            assertEquals(1.0, registry.counter("risk.decision.log.dropped").count());
        } finally { unblock.countDown(); logger.stop(); }
    }
    @Test
    void tableUsesUtcServerDate() {
        assertEquals("decision_log_20261009", JdbcDecisionLogSink.tableName(
            java.time.Instant.parse("2026-10-09T23:59:59Z").toEpochMilli()));
    }
}
