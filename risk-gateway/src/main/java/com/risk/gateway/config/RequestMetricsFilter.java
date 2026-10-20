package com.risk.gateway.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 包含异步排队、幂等写入及响应序列化；不包含客户端网络 RTT。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestMetricsFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;
    public RequestMetricsFilter(MeterRegistry registry) { this.registry=registry; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        long start=System.nanoTime();
        AtomicBoolean recorded=new AtomicBoolean();
        String path=request.getServletPath();
        String endpoint=path.equals("/api/v1/risk/order") ? "order"
            : path.startsWith("/api/v1/admin/rules/") && path.endsWith("/test") ? "rule_test" : "other";
        Runnable record=() -> {
            if (recorded.compareAndSet(false, true)) {
                Timer.builder("risk.http.request.duration").tag("endpoint", endpoint)
                    .tag("status", Integer.toString(response.getStatus()))
                    .publishPercentileHistogram().serviceLevelObjectives(java.time.Duration.ofMillis(5))
                    .register(registry).record(System.nanoTime()-start, TimeUnit.NANOSECONDS);
            }
        };
        try { chain.doFilter(request, response); }
        finally {
            if (!request.isAsyncStarted()) record.run();
            else {
                AsyncListener listener=new AsyncListener() {
                    public void onComplete(AsyncEvent event) { record.run(); }
                    public void onTimeout(AsyncEvent event) {
                        registry.counter("risk.http.async.timeout", "endpoint", endpoint).increment();
                    }
                    public void onError(AsyncEvent event) {
                        registry.counter("risk.http.async.error", "endpoint", endpoint).increment();
                    }
                    public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                };
                try { request.getAsyncContext().addListener(listener); }
                catch (IllegalStateException completed) { record.run(); }
            }
        }
    }
}
