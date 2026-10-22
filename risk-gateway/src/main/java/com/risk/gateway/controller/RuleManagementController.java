package com.risk.gateway.controller;

import com.risk.gateway.Models.ApiResponse;
import com.risk.gateway.config.ApiSecurityFilter;
import com.risk.gateway.service.RuleManagementService;
import com.risk.gateway.service.RuleManagementService.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/admin/rules")
@ConditionalOnProperty(name="risk.rules.enabled", havingValue="true", matchIfMissing=true)
public class RuleManagementController {
    private final RuleManagementService service;
    private final Executor executor;
    public RuleManagementController(RuleManagementService service, @Qualifier("adminExecutor") Executor executor) {
        this.service = service; this.executor = executor;
    }
    @GetMapping("/{id}/draft")
    public CompletableFuture<ApiResponse<?>> draft(@PathVariable String id, HttpServletRequest request) {
        return submit(request, () -> service.draft(id));
    }
    @PutMapping("/{id}/draft")
    public CompletableFuture<ApiResponse<?>> save(@PathVariable String id, @Valid @RequestBody DraftRequest body, HttpServletRequest request) {
        String trace = trace(request);
        String actor=com.risk.gateway.config.AdminIdentities.actor(request);
        return submit(request, () -> service.save(id, body, actor, trace));
    }
    @PostMapping("/{id}/approve")
    public CompletableFuture<ApiResponse<?>> approve(@PathVariable String id, @Valid @RequestBody ApprovalRequest body, HttpServletRequest request) {
        String trace=trace(request), actor=com.risk.gateway.config.AdminIdentities.actor(request);
        return submit(request,()->service.approve(id,body,actor,trace));
    }
    @PostMapping("/{id}/publish")
    public CompletableFuture<ApiResponse<?>> publish(@PathVariable String id, @Valid @RequestBody PublishRequest body, HttpServletRequest request) {
        String trace = trace(request);
        String actor=com.risk.gateway.config.AdminIdentities.actor(request);
        return submit(request, () -> service.publish(id, body, actor, trace));
    }
    @GetMapping("/{id}/state")
    public CompletableFuture<ApiResponse<?>> status(@PathVariable String id, HttpServletRequest request) {
        return submit(request, () -> service.status(id));
    }
    @PutMapping("/{id}/state")
    public CompletableFuture<ApiResponse<?>> state(@PathVariable String id, @Valid @RequestBody StateRequest body, HttpServletRequest request) {
        String trace = trace(request);
        String actor=com.risk.gateway.config.AdminIdentities.actor(request);
        return submit(request, () -> service.setEnabled(id, body, actor, trace));
    }
    @PostMapping("/{id}/rollback")
    public CompletableFuture<ApiResponse<?>> rollback(@PathVariable String id, @Valid @RequestBody RollbackRequest body, HttpServletRequest request) {
        String trace = trace(request);
        String actor=com.risk.gateway.config.AdminIdentities.actor(request);
        return submit(request, () -> service.rollback(id, body, actor, trace));
    }
    @GetMapping("/{id}/versions")
    public CompletableFuture<ApiResponse<?>> versions(@PathVariable String id, @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        return submit(request, () -> service.history(id, limit));
    }
    @GetMapping("/{id}/audit")
    public CompletableFuture<ApiResponse<?>> audit(@PathVariable String id, @RequestParam(defaultValue="20") int limit, HttpServletRequest request) {
        return submit(request, () -> service.audits(id, limit));
    }
    private CompletableFuture<ApiResponse<?>> submit(HttpServletRequest request, Supplier<?> operation) {
        String trace = trace(request);
        return CompletableFuture.supplyAsync(() -> ApiResponse.of(0, "success", trace, operation.get()), executor);
    }
    private static String trace(HttpServletRequest request) {
        return (String) request.getAttribute(ApiSecurityFilter.TRACE);
    }
}
