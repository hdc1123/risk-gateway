package com.risk.gateway.service;

import com.risk.gateway.Models.*;
import com.risk.gateway.adapter.*;
import com.risk.gateway.engine.GroovyRuleEngine;
import com.risk.gateway.feature.*;
import com.risk.gateway.idempotency.*;
import com.risk.gateway.routing.RoutingDecider;
import com.risk.gateway.logging.DecisionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
@ConditionalOnProperty(name={"risk.rules.enabled", "risk.redis.enabled"}, havingValue="true", matchIfMissing=true)
public class OrderRiskService {
    private static final Logger log = LoggerFactory.getLogger(OrderRiskService.class);
    private final FeatureAssembler features;
    private final GroovyRuleEngine engine;
    private final RoutingDecider decider;
    private final MT5Adapter adapter;
    private final IdempotencyStore idempotency;
    private final DecisionLogger decisionLogger;
    public OrderRiskService(FeatureAssembler features, GroovyRuleEngine engine, RoutingDecider decider,
                            MT5Adapter adapter, IdempotencyStore idempotency, DecisionLogger decisionLogger) {
        this.features=features; this.engine=engine; this.decider=decider; this.adapter=adapter; this.idempotency=idempotency;
        this.decisionLogger=decisionLogger;
    }

    public ApiResponse<ResponseData> handle(OrderRequest order, String requestId, String trace, long start) {
        var claim = idempotency.claim(requestId, order);
        if (claim.status() != IdempotencyStore.Status.ACQUIRED) {
            return ApiResponse.of(claim.status() == IdempotencyStore.Status.CONFLICT ? 1003 : 1002,
                "idempotency_" + claim.status().name().toLowerCase(Locale.ROOT), trace, claim.cached());
        }
        boolean dispatched = false;
        Decision decision = new Decision(Book.B_BOOK, "默认 B_BOOK", List.of());
        Evaluation evaluation = new Evaluation(List.of(), 0, true);
        String degrade = null;
        Map<String, Object> assembled = Map.of();
        try {
            try {
                assembled = features.assemble(order);
                evaluation = engine.evaluateOrder(assembled, order.userId()+":"+order.mt5Account());
            }
            catch (FeatureUnavailableException e) { degrade = e.getMessage(); }
            decision = evaluation.degraded() && evaluation.hits().isEmpty()
                ? new Decision(Book.B_BOOK, "异常降级，安全默认 B_BOOK", List.of())
                : decider.decide(evaluation);
            if (evaluation.degraded() && degrade == null) degrade = "RULE_EVALUATION_DEGRADED";
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("CALLER_INTERRUPTED");
            dispatched = true; // 必须在调用前设置，调用抛异常也可能产生交易副作用。
            RouteResult result = adapter.route(decision, order, trace);
            ResponseData data = data(order, decision, result.adapterOrderId(), start, evaluation, degrade, "ALLOW");
            try { idempotency.complete(claim, data); }
            catch (IdempotencyUnavailableException e) {
                log.error("Adapter 已受理但幂等结果写入失败 traceId={}, orderId={}", trace, order.orderId(), e);
                uncertain(claim, trace);
                return finish(1504, "adapter_accepted_idempotency_uncertain", order, trace, assembled, evaluation, decision,
                    data(order, decision, result.adapterOrderId(), start, evaluation, "IDEMPOTENCY_WRITE_FAILED", "ALLOW"));
            }
            return finish(0, "success", order, trace, assembled, evaluation, decision, data);
        } catch (AdapterException e) {
            if (e.outcome() == AdapterException.Outcome.NOT_DISPATCHED) release(claim, trace);
            else uncertain(claim, trace); // 明确拒绝也保留占位，防止重放持续产生调用。
            return finish(e.outcome() == AdapterException.Outcome.REJECTED ? 1505 : 1504,
                e.reason(), order, trace, assembled, evaluation, decision, data(order, decision, null, start, evaluation, e.reason(),
                    e.outcome() == AdapterException.Outcome.UNCERTAIN ? "UNKNOWN" : "NOT_EXECUTED"));
        } catch (RuntimeException e) {
            if (dispatched) uncertain(claim, trace); else release(claim, trace);
            log.error("订单处理失败 traceId={}, orderId={}", trace, order.orderId(), e);
            return finish(1500, "risk_error", order, trace, assembled, evaluation, decision,
                data(order, decision, null, start, evaluation, "RISK_ERROR", dispatched ? "UNKNOWN" : "NOT_EXECUTED"));
        }
    }

    private ApiResponse<ResponseData> finish(int code, String message, OrderRequest order, String trace,
        Map<String, Object> assembled, Evaluation evaluation, Decision decision, ResponseData response) {
        try {
            decisionLogger.logAsync(new DecisionContext(order, trace, assembled, evaluation, decision, response));
        } catch (RuntimeException e) {
            // 日志失败不能触发交易重投或覆盖已受理结果。
            log.error("决策日志入队异常 traceId={}, orderId={}", trace, order.orderId(), e);
        }
        return ApiResponse.of(code, message, trace, response);
    }

    private void uncertain(IdempotencyStore.Claim claim, String trace) {
        try { idempotency.markUncertain(claim); }
        catch (RuntimeException e) { log.error("标记不确定状态失败，禁止重投 traceId={}", trace, e); }
    }
    private void release(IdempotencyStore.Claim claim, String trace) {
        try { idempotency.releaseBeforeDispatch(claim); }
        catch (RuntimeException e) { log.error("发送前释放占位失败 traceId={}", trace, e); }
    }
    private ResponseData data(OrderRequest order, Decision decision, String adapterId, long start,
                              Evaluation evaluation, String degrade, String action) {
        return new ResponseData(order.eventId(), order.orderId(), decision.bookType(), action, "L0",
            decision.reason(), decision.ruleIds(), adapterId, (System.nanoTime()-start)/1_000_000.0,
            evaluation.latencyMs(), degrade != null, degrade, System.currentTimeMillis());
    }
}
