package com.risk.gateway.controller;

import com.risk.gateway.Models.*;
import com.risk.gateway.config.ApiSecurityFilter;
import com.risk.gateway.service.RuleTestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@RestController
@ConditionalOnProperty(name="risk.rules.enabled", havingValue="true", matchIfMissing=true)
public class RuleTestController {
    private final RuleTestService service;
    private final Executor executor;
    public RuleTestController(RuleTestService service, @Qualifier("adminExecutor") Executor executor) {
        this.service = service; this.executor = executor;
    }
    @PostMapping("/api/v1/admin/rules/{ruleId}/test")
    public CompletableFuture<ApiResponse<ScriptResult>> test(@PathVariable String ruleId,
        @Valid @RequestBody TestRequest body, HttpServletRequest request) {
        String trace = (String) request.getAttribute(ApiSecurityFilter.TRACE);
        return CompletableFuture.supplyAsync(() -> ApiResponse.of(0, "success", trace, service.test(ruleId, body)), executor);
    }
}
