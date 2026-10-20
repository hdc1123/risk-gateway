package com.risk.gateway.adapter;

import com.risk.gateway.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.stub.ClientCalls;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name = "risk.adapter-mode", havingValue = "grpc")
public class GrpcMT5Adapter implements MT5Adapter {
    private final ManagedChannel a;
    private final ManagedChannel b;
    private final com.risk.gateway.config.SystemConfigService configs;
    private final MethodDescriptor<AdapterProtocol.Request, AdapterProtocol.Reply> method;
    private final String token;

    public GrpcMT5Adapter(ObjectMapper mapper, Environment environment,
        @Value("${risk.adapter-a-target}") String aTarget,
        @Value("${risk.adapter-b-target}") String bTarget,
        @Value("${risk.adapter-plaintext:false}") boolean plaintext,
        com.risk.gateway.config.SystemConfigService configs,
        @Value("${risk.adapter-token:}") String token) {
        if (plaintext && !environment.acceptsProfiles(Profiles.of("local", "test")))
            throw new IllegalArgumentException("明文 gRPC 仅允许 local/test");
        if (!environment.acceptsProfiles(Profiles.of("local", "test")) && token.isBlank())
            throw new IllegalArgumentException("真实 Adapter 必须配置访问凭证");
        this.configs = configs;
        this.token = token;
        this.method = AdapterProtocol.method(mapper);
        this.a = channel(aTarget, plaintext);
        ManagedChannel second;
        try { second = channel(bTarget, plaintext); }
        catch (RuntimeException e) { a.shutdownNow(); throw e; }
        this.b = second;
    }

    private static ManagedChannel channel(String target, boolean plaintext) {
        if (target == null || target.isBlank()) throw new IllegalArgumentException("Adapter 地址为空");
        var builder = ManagedChannelBuilder.forTarget(target).disableRetry().maxInboundMessageSize(65536);
        if (plaintext) builder.usePlaintext(); else builder.useTransportSecurity();
        return builder.build();
    }

    @Override
    public RouteResult route(Decision decision, OrderRequest order, String traceId) {
        if (decision == null || decision.bookType() == null || order == null)
            throw new AdapterException(AdapterException.Outcome.NOT_DISPATCHED, "ADAPTER_INVALID_REQUEST", null);
        int timeoutMs = configs.current().adapterTimeoutMs();
        String businessKey = AdapterProtocol.businessKey(order);
        var request = new AdapterProtocol.Request(1, businessKey, traceId, decision.bookType(), order);
        ManagedChannel channel = decision.bookType() == Book.A_BOOK ? a : b;
        Metadata headers = new Metadata();
        if (!token.isBlank()) headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        Channel authenticated = ClientInterceptors.intercept(channel, io.grpc.stub.MetadataUtils.newAttachHeadersInterceptor(headers));
        Future<AdapterProtocol.Reply> future = null;
        long start = System.nanoTime();
        try {
            future = ClientCalls.futureUnaryCall(authenticated.newCall(method,
                CallOptions.DEFAULT.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)), request);
            long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs) - (System.nanoTime() - start);
            if (remaining <= 0) throw new TimeoutException();
            var reply = future.get(remaining, TimeUnit.NANOSECONDS);
            return validateReply(reply, request);
        } catch (InterruptedException e) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AdapterException(AdapterException.Outcome.UNCERTAIN, "ADAPTER_INTERRUPTED", e);
        } catch (TimeoutException e) {
            if (future != null) future.cancel(true);
            throw new AdapterException(AdapterException.Outcome.UNCERTAIN, "ADAPTER_TIMEOUT", e);
        } catch (ExecutionException e) {
            throw new AdapterException(AdapterException.Outcome.UNCERTAIN, "ADAPTER_RPC_FAILED", e.getCause());
        } catch (AdapterException e) { throw e; }
        catch (RuntimeException e) {
            if (future != null) future.cancel(true);
            throw new AdapterException(AdapterException.Outcome.UNCERTAIN, "ADAPTER_PROTOCOL_ERROR", e);
        }
    }

    static RouteResult validateReply(AdapterProtocol.Reply reply, AdapterProtocol.Request request) {
        if (reply == null || reply.version() != 1 || !request.idempotencyKey().equals(reply.idempotencyKey())
            || request.bookType() != reply.bookType())
            throw new AdapterException(AdapterException.Outcome.UNCERTAIN, "ADAPTER_RESPONSE_MISMATCH", null);
        // REJECTED 必须表示服务端确定未产生外部订单，不能用来表示未知或部分成功。
        if ("REJECTED".equals(reply.status()))
            throw new AdapterException(AdapterException.Outcome.REJECTED, "ADAPTER_REJECTED", null);
        if (!("ACCEPTED".equals(reply.status()) || "DUPLICATE".equals(reply.status()))
            || reply.adapterOrderId() == null || reply.adapterOrderId().isBlank() || reply.adapterOrderId().length() > 128)
            throw new AdapterException(AdapterException.Outcome.UNCERTAIN, "ADAPTER_INVALID_RESPONSE", null);
        return new RouteResult(reply.adapterOrderId());
    }

    @PreDestroy
    public void close() {
        a.shutdownNow();
        b.shutdownNow();
    }
}
