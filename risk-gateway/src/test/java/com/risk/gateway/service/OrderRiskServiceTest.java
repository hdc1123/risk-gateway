package com.risk.gateway.service;

import com.risk.gateway.Models.*;
import com.risk.gateway.adapter.*;
import com.risk.gateway.engine.GroovyRuleEngine;
import com.risk.gateway.feature.*;
import com.risk.gateway.idempotency.*;
import com.risk.gateway.routing.RoutingDecider;
import com.risk.gateway.logging.DecisionLogger;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OrderRiskServiceTest {
    private final FeatureAssembler features = mock(FeatureAssembler.class);
    private final GroovyRuleEngine engine = mock(GroovyRuleEngine.class);
    private final MT5Adapter adapter = mock(MT5Adapter.class);
    private final IdempotencyStore store = mock(IdempotencyStore.class);
    private final DecisionLogger logger = mock(DecisionLogger.class);
    private final OrderRiskService service = new OrderRiskService(features, engine, new RoutingDecider(), adapter, store, logger);
    private final OrderRequest order = new OrderRequest("evt", "ORDER_SUBMIT", 1L, 2L, "ord", "EURUSD",
        new BigDecimal("1.00"), "BUY", new BigDecimal("1.08"), "MARKET", 1L);
    private final IdempotencyStore.Claim claim = new IdempotencyStore.Claim(IdempotencyStore.Status.ACQUIRED, "key", "owner", null);

    private void acquired() {
        when(store.claim("req", order)).thenReturn(claim);
        when(features.assemble(order)).thenReturn(Map.of());
        when(engine.evaluate(anyMap())).thenReturn(new Evaluation(List.of(), 0.1, false));
    }
    @Test
    void successCachesActualAdapterResponse() {
        acquired();
        when(adapter.route(any(), eq(order), eq("trace"))).thenReturn(new RouteResult("adp"));
        var response = service.handle(order, "req", "trace", System.nanoTime());
        assertEquals(0, response.code());
        assertEquals(Book.B_BOOK, response.data().bookType());
        assertEquals("adp", response.data().adapterOrderId());
        verify(store).complete(claim, response.data());
        verify(logger).logAsync(argThat(context -> context.response().equals(response.data()) && context.order().equals(order)));
    }
    @Test
    void processingDuplicateNeverDispatches() {
        when(store.claim("req", order)).thenReturn(new IdempotencyStore.Claim(IdempotencyStore.Status.PROCESSING, "key", null, null));
        assertEquals(1002, service.handle(order, "req", "trace", System.nanoTime()).code());
        verifyNoInteractions(features, engine, adapter);
    }
    @Test
    void featureFailureRoutesDefaultWithDegradeFlag() {
        acquired();
        when(features.assemble(order)).thenThrow(new FeatureUnavailableException("FEATURE_STALE"));
        when(adapter.route(any(), eq(order), eq("trace"))).thenReturn(new RouteResult("adp"));
        var response = service.handle(order, "req", "trace", System.nanoTime());
        assertTrue(response.data().degradeFlag());
        assertEquals("FEATURE_STALE", response.data().degradeReason());
        assertEquals(Book.B_BOOK, response.data().bookType());
        verifyNoInteractions(engine);
    }
    @Test
    void adapterTimeoutPreservesClaimAndDoesNotRetry() {
        acquired();
        when(adapter.route(any(), eq(order), eq("trace"))).thenThrow(
            new AdapterException(AdapterException.Outcome.UNCERTAIN, "ADAPTER_TIMEOUT", null));
        var response = service.handle(order, "req", "trace", System.nanoTime());
        assertEquals(1504, response.code());
        assertEquals("UNKNOWN", response.data().action());
        verify(store).markUncertain(claim);
        verify(store, never()).releaseBeforeDispatch(any());
        verify(adapter, times(1)).route(any(), any(), any());
    }
}
