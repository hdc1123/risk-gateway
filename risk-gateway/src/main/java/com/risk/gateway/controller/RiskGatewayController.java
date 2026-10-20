package com.risk.gateway.controller;

import com.risk.gateway.Models.*;
import com.risk.gateway.config.ApiSecurityFilter;
import com.risk.gateway.service.OrderRiskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@RestController
@ConditionalOnProperty(name={"risk.rules.enabled", "risk.redis.enabled"}, havingValue="true", matchIfMissing=true)
public class RiskGatewayController {
    private final OrderRiskService service;
    private final Executor executor;
    public RiskGatewayController(OrderRiskService service, @Qualifier("riskExecutor") Executor executor) {
        this.service = service; this.executor = executor;
    }
    @PostMapping("/api/v1/risk/order")
    public CompletableFuture<ApiResponse<ResponseData>> order(@Valid @RequestBody OrderRequest order,
        @RequestHeader("X-Request-Id") String requestId, HttpServletRequest request) {
        if (!requestId.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("请求ID格式错误");
        String trace = (String) request.getAttribute(ApiSecurityFilter.TRACE);
        Long start = (Long) request.getAttribute(ApiSecurityFilter.START);
        long began = start == null ? System.nanoTime() : start;
        return CompletableFuture.supplyAsync(() -> service.handle(order, requestId, trace, began), executor);
    }
}
