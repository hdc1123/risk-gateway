package com.risk.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiSecurityFilterTest {
    @Test
    void rejectsMissingOrWrongRoleToken() throws Exception {
        var filter = new ApiSecurityFilter(new ObjectMapper(), "gateway", "admin");
        var request = new MockHttpServletRequest("POST", "/api/v1/admin/rules/A/test");
        request.setServletPath("/api/v1/admin/rules/A/test");
        request.addHeader("Authorization", "Bearer gateway");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(401, response.getStatus());
        assertNull(chain.getRequest());
    }
    @Test
    void acceptsCorrectTokenAndSetsTrace() throws Exception {
        var filter = new ApiSecurityFilter(new ObjectMapper(), "gateway", "admin");
        var request = new MockHttpServletRequest("POST", "/api/v1/risk/order");
        request.setServletPath("/api/v1/risk/order");
        request.addHeader("Authorization", "Bearer gateway");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest());
        assertNotNull(response.getHeader("X-Trace-Id"));
    }
}
