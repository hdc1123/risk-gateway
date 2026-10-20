package com.risk.gateway.config;

import com.risk.gateway.Models.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** 服务间 bearer 鉴权；外部部署仍须 TLS 与网络访问控制。 */
@Component
public class ApiSecurityFilter extends OncePerRequestFilter {
    public static final String TRACE = "risk.traceId";
    public static final String START = "risk.startNanos";
    private final String gatewayToken;
    private final String adminToken;
    private final ObjectMapper mapper;
    public ApiSecurityFilter(ObjectMapper mapper, @Value("${risk.gateway-token:}") String gatewayToken,
                             @Value("${risk.admin-token:}") String adminToken) {
        this.mapper = mapper; this.gatewayToken = gatewayToken; this.adminToken = adminToken;
    }
    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return true; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String trace = request.getHeader("X-Trace-Id");
        if (trace == null || !trace.matches("[A-Za-z0-9._:-]{1,64}")) trace = UUID.randomUUID().toString();
        request.setAttribute(TRACE, trace);
        request.setAttribute(START, System.nanoTime());
        response.setHeader("X-Trace-Id", trace);
        String path = request.getServletPath();
        String expected = path.startsWith("/api/v1/admin/") ? adminToken
            : path.startsWith("/api/v1/risk/") ? gatewayToken : null;
        if (expected != null) {
            String supplied = request.getHeader("Authorization");
            boolean valid = !expected.isBlank() && supplied != null && MessageDigest.isEqual(
                ("Bearer " + expected).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
            if (!valid) {
                response.setStatus(401); response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(), ApiResponse.of(1401, "unauthorized", trace, null));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
