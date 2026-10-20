package com.risk.gateway.idempotency;

import com.risk.gateway.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Java 协议边界测试；Lua 原子性还需真实 Redis 集成测试，mock 不证明原子性。 */
class RedisIdempotencyStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final RedisIdempotencyStore store = new RedisIdempotencyStore(redis, new ObjectMapper(), 86400);
    private final OrderRequest order = new OrderRequest("evt", "ORDER_SUBMIT", 1L, 2L, "ord", "EURUSD",
        new BigDecimal("1.00"), "BUY", new BigDecimal("1.08"), "MARKET", 1L);

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void reply(String status) {
        doReturn(List.of(status, "")).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
    @Test
    void acquiredClaimHasOwnerAndStableBusinessKey() {
        reply("ACQUIRED");
        var first = store.claim("req-1", order);
        var second = store.claim("req-2", order);
        assertEquals(IdempotencyStore.Status.ACQUIRED, first.status());
        assertNotNull(first.ownerToken());
        assertEquals(first.key(), second.key());
        assertNotEquals(first.ownerToken(), second.ownerToken());
    }
    @Test
    void duplicateDoesNotOwnClaim() {
        reply("PROCESSING");
        var claim = store.claim("req", order);
        assertNull(claim.ownerToken());
        assertThrows(IllegalArgumentException.class, () -> store.releaseBeforeDispatch(claim));
    }
    @Test
    void rejectsInvalidHeaderAndUnavailableRedis() {
        assertThrows(IllegalArgumentException.class, () -> store.claim("bad header", order));
        assertThrows(IdempotencyUnavailableException.class, () -> store.claim("req", order));
    }
}
