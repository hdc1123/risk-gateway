package com.risk.gateway;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

public final class Models {
    private Models() {}
    public enum Book { A_BOOK, B_BOOK }
    public record OrderRequest(
        @NotBlank @Size(max=64) String eventId,
        @NotBlank @Pattern(regexp="ORDER_SUBMIT") String eventType,
        @NotNull @Positive Long userId, @NotNull @Positive Long mt5Account,
        @NotBlank @Size(max=64) String orderId, @NotBlank @Size(max=32) String symbol,
        @NotNull @DecimalMin("0.01") @Digits(integer=16, fraction=2) BigDecimal volume,
        @NotBlank @Pattern(regexp="BUY|SELL") String side,
        @NotNull @DecimalMin(value="0", inclusive=false) BigDecimal price,
        @NotBlank @Pattern(regexp="MARKET|LIMIT|STOP") String orderType,
        @NotNull @Positive Long timestamp) {}
    public record Rule(String ruleId, String ruleName, int priority, String scriptContent,
                       String scriptMd5, String returnType, int timeoutMs) {}
    public record CompiledRule(Rule rule, Class<? extends groovy.lang.Script> scriptClass, String digest) {}
    public record RuleResult(String ruleId, String ruleName, int priority, String action, String target, Book value) {}
    public record ScriptResult(String ruleId, String scriptMd5, Object returnValue, RuleResult result,
                               double latencyMs, boolean compiled, String error) {}
    public record Evaluation(List<ScriptResult> scripts, double latencyMs, boolean degraded) {
        public Evaluation { scripts = List.copyOf(scripts); }
        public List<RuleResult> hits() { return scripts.stream().map(ScriptResult::result).filter(Objects::nonNull).toList(); }
    }
    public record Decision(Book bookType, String reason, List<String> ruleIds) {}
    public record RouteResult(String adapterOrderId) {}
    public record ResponseData(String eventId, String orderId, Book bookType, String action,
                               String actionLevel, String reason, List<String> ruleIds, String adapterOrderId,
                               double latencyMs, double scriptLatencyMs, boolean degradeFlag,
                               String degradeReason, long timestamp) {}
    public record ApiResponse<T>(int code, String message, String traceId, long timestamp, T data) {
        public static <T> ApiResponse<T> of(int code, String message, String trace, T data) {
            return new ApiResponse<>(code, message, trace, System.currentTimeMillis(), data);
        }
    }
    public record TestRequest(@NotNull Map<String,Object> features, Object profile) {}
    public record DecisionContext(OrderRequest order, String traceId, Map<String,Object> features,
                                  Evaluation evaluation, Decision decision, ResponseData response) {}
}
