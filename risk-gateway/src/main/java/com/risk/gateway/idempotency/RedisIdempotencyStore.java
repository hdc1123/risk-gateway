package com.risk.gateway.idempotency;

import com.risk.gateway.Models.OrderRequest;
import com.risk.gateway.Models.ResponseData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Component
@ConditionalOnProperty(name = "risk.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisIdempotencyStore implements IdempotencyStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final long retentionSeconds;
    private final DefaultRedisScript<List> claimScript;
    private final DefaultRedisScript<Long> transitionScript;

    public RedisIdempotencyStore(StringRedisTemplate redis, ObjectMapper mapper,
                                @Value("${risk.idempotency-ttl-seconds:86400}") long retentionSeconds) {
        if (retentionSeconds < 60) throw new IllegalArgumentException("幂等结果保留期至少 60 秒");
        this.redis = redis;
        this.mapper = mapper;
        this.retentionSeconds = retentionSeconds;
        claimScript = new DefaultRedisScript<>();
        claimScript.setLocation(new ClassPathResource("redis/idempotency-claim.lua"));
        claimScript.setResultType(List.class);
        transitionScript = new DefaultRedisScript<>();
        transitionScript.setLocation(new ClassPathResource("redis/idempotency-transition.lua"));
        transitionScript.setResultType(Long.class);
    }

    @Override
    public Claim claim(String requestId, OrderRequest order) {
        if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("X-Request-Id 格式不合法");
        Objects.requireNonNull(order, "order");
        String prefix = "risk:idem:v1:{" + order.userId() + ":" + order.mt5Account() + "}:";
        String key = prefix + "order:" + sha256(order.orderId());
        String requestKey = prefix + "request:" + sha256(requestId);
        String owner = UUID.randomUUID().toString();
        try {
            // 字段顺序由 DTO record 固定；包括 eventId/timestamp，重试必须保留原始请求。
            String fingerprint = sha256(mapper.writeValueAsString(order));
            List<?> result = redis.execute(claimScript, List.of(key, requestKey), fingerprint, owner,
                requestId, Long.toString(System.currentTimeMillis()), Long.toString(retentionSeconds));
            if (result == null || result.size() != 2) throw new IdempotencyUnavailableException("INVALID_CLAIM_RESULT");
            Status status = Status.valueOf(result.get(0).toString());
            ResponseData cached = status == Status.COMPLETED
                ? mapper.readValue(result.get(1).toString(), ResponseData.class) : null;
            return new Claim(status, key, status == Status.ACQUIRED ? owner : null, cached);
        } catch (JsonProcessingException | RuntimeException e) {
            if (e instanceof IdempotencyUnavailableException unavailable) throw unavailable;
            throw new IdempotencyUnavailableException("IDEMPOTENCY_CLAIM_FAILED", e);
        }
    }

    @Override
    public void complete(Claim claim, ResponseData response) {
        Objects.requireNonNull(response, "response");
        try { transition(claim, "COMPLETED", mapper.writeValueAsString(response)); }
        catch (JsonProcessingException e) { throw new IdempotencyUnavailableException("IDEMPOTENCY_SERIALIZATION_FAILED", e); }
    }

    @Override
    public void releaseBeforeDispatch(Claim claim) { transition(claim, "RELEASE", ""); }

    @Override
    public void markUncertain(Claim claim) { transition(claim, "UNCERTAIN", ""); }

    private void transition(Claim claim, String action, String response) {
        if (claim == null || claim.status() != Status.ACQUIRED || claim.ownerToken() == null)
            throw new IllegalArgumentException("仅占位持有者可以更新状态");
        try {
            Long changed = redis.execute(transitionScript, List.of(claim.key()), claim.ownerToken(), action,
                response, Long.toString(retentionSeconds));
            if (!Long.valueOf(1).equals(changed)) throw new IdempotencyUnavailableException("IDEMPOTENCY_OWNERSHIP_LOST");
        } catch (RuntimeException e) {
            if (e instanceof IdempotencyUnavailableException unavailable) throw unavailable;
            throw new IdempotencyUnavailableException("IDEMPOTENCY_TRANSITION_FAILED", e);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
