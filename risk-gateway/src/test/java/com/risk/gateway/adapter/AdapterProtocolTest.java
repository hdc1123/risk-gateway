package com.risk.gateway.adapter;

import com.risk.gateway.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class AdapterProtocolTest {
    private final OrderRequest order = new OrderRequest("evt", "ORDER_SUBMIT", 1L, 2L, "ord", "EURUSD",
        new BigDecimal("1.00"), "BUY", new BigDecimal("1.08"), "MARKET", 1L);
    private final AdapterProtocol.Request request = new AdapterProtocol.Request(1, "1:2:ord", "trace", Book.A_BOOK, order);

    @Test
    void jsonWireRoundTrip() {
        var method = AdapterProtocol.method(new ObjectMapper());
        var marshaller = method.getRequestMarshaller();
        assertEquals(request, marshaller.parse(marshaller.stream(request)));
        assertEquals("risk.adapter.v1.OrderRouter/Route", method.getFullMethodName());
    }
    @Test
    void validatesAcceptedDuplicateAndMismatch() {
        assertEquals("adp", GrpcMT5Adapter.validateReply(
            new AdapterProtocol.Reply(1, "1:2:ord", Book.A_BOOK, "ACCEPTED", "adp"), request).adapterOrderId());
        assertEquals("adp", GrpcMT5Adapter.validateReply(
            new AdapterProtocol.Reply(1, "1:2:ord", Book.A_BOOK, "DUPLICATE", "adp"), request).adapterOrderId());
        var mismatch = assertThrows(AdapterException.class, () -> GrpcMT5Adapter.validateReply(
            new AdapterProtocol.Reply(1, "other", Book.A_BOOK, "ACCEPTED", "adp"), request));
        assertEquals(AdapterException.Outcome.UNCERTAIN, mismatch.outcome());
        assertEquals(AdapterException.Outcome.REJECTED, assertThrows(AdapterException.class, () ->
            GrpcMT5Adapter.validateReply(new AdapterProtocol.Reply(1, "1:2:ord", Book.A_BOOK, "REJECTED", null), request)).outcome());
    }
    @Test
    void mockCannotRunWithOnlyProductionProfile() {
        var env = new MockEnvironment();
        env.setActiveProfiles("prod");
        assertThrows(IllegalArgumentException.class, () -> new MockMT5Adapter(env));
        env.setActiveProfiles("local");
        var adapter = new MockMT5Adapter(env);
        var decision = new Decision(Book.A_BOOK, "test", java.util.List.of());
        assertEquals(adapter.route(decision, order, "t1"), adapter.route(decision, order, "t2"));
    }
}
