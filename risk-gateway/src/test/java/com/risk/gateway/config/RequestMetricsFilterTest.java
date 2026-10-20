package com.risk.gateway.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestMetricsFilterTest {
    @Test void recordsSynchronousResponseOnce() throws Exception {
        var registry = new SimpleMeterRegistry();
        var request = new MockHttpServletRequest("POST", "/api/v1/risk/order");
        request.setServletPath("/api/v1/risk/order");
        var response = new MockHttpServletResponse();
        new RequestMetricsFilter(registry).doFilter(request, response, (req, res) -> response.setStatus(401));
        assertEquals(1, registry.get("risk.http.request.duration").tag("endpoint", "order").tag("status", "401").timer().count());
    }
    @Test void recordsAsyncResponseOnlyOnCompletion() throws Exception {
        var registry = new SimpleMeterRegistry();
        var request = new MockHttpServletRequest("POST", "/api/v1/risk/order");
        request.setServletPath("/api/v1/risk/order"); request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        new RequestMetricsFilter(registry).doFilter(request, response, (req, res) -> req.startAsync(req, res));
        assertNull(registry.find("risk.http.request.duration").timer());
        ((MockAsyncContext) request.getAsyncContext()).complete();
        assertEquals(1, registry.get("risk.http.request.duration").tag("endpoint", "order").timer().count());
    }
}
