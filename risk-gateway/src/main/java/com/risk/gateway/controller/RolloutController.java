package com.risk.gateway.controller;

import com.risk.gateway.Models.ApiResponse;
import com.risk.gateway.config.ApiSecurityFilter;
import com.risk.gateway.rule.RolloutService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/v1/admin/rollout")
@ConditionalOnProperty(name="risk.rules.enabled",havingValue="true",matchIfMissing=true)
public class RolloutController {
    private final RolloutService service;
    private final Executor executor;
    public RolloutController(RolloutService service,@Qualifier("adminExecutor") Executor executor) {
        this.service=service; this.executor=executor;
    }
    @GetMapping public ApiResponse<RolloutService.Status> status(HttpServletRequest request) {
        return ApiResponse.of(0,"success",(String) request.getAttribute(ApiSecurityFilter.TRACE),service.status());
    }
    @PutMapping public CompletableFuture<ApiResponse<Integer>> change(@Valid @RequestBody RolloutService.Change change,HttpServletRequest request) {
        String trace=(String) request.getAttribute(ApiSecurityFilter.TRACE);
        String actor=com.risk.gateway.config.AdminIdentities.actor(request);
        return CompletableFuture.supplyAsync(()->ApiResponse.of(0,"success",trace,service.configure(change,actor,trace)),executor);
    }
}
