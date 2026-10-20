package com.risk.gateway.feature;

import com.risk.gateway.Models.OrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FeatureAssemblerTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String,Object,Object> hashes = mock(HashOperations.class);
    private final Instant now = Instant.parse("2026-10-09T12:00:00Z");
    private final FeatureAssembler assembler = new FeatureAssembler(redis, 5000, Clock.fixed(now, ZoneOffset.UTC));
    private final OrderRequest order = new OrderRequest("evt", "ORDER_SUBMIT", 1L, 2L, "ord", "EURUSD",
        new BigDecimal("1.00"), "BUY", new BigDecimal("1.08"), "MARKET", now.toEpochMilli());

    private void snapshot(List<Object> values) {
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.multiGet(eq("risk:features:v1:{1:2}"), anyCollection())).thenReturn(values);
    }
    @Test
    void readsTypedImmutableFeatures() {
        snapshot(List.of("2", "6", "150.25", Long.toString(now.toEpochMilli()), "2026-10-09"));
        var features = assembler.assemble(order);
        assertEquals(6L, features.get("tradesIn5Min"));
        assertEquals(new BigDecimal("150.25"), features.get("dailyProfit"));
        assertEquals(order.volume(), features.get("volume"));
        assertThrows(UnsupportedOperationException.class, () -> features.put("volume", 0));
    }
    @Test
    void missingAndStaleDataFailExplicitly() {
        snapshot(Arrays.asList(null, null, null, null, null));
        assertEquals("FEATURE_MISSING", assertThrows(FeatureUnavailableException.class, () -> assembler.assemble(order)).getMessage());
        snapshot(List.of("2", "6", "0", Long.toString(now.minusSeconds(6).toEpochMilli()), "2026-10-09"));
        assertEquals("FEATURE_STALE", assertThrows(FeatureUnavailableException.class, () -> assembler.assemble(order)).getMessage());
    }
    @Test
    void rejectsInconsistentCountsAndWrongDay() {
        snapshot(List.of("7", "6", "0", Long.toString(now.toEpochMilli()), "2026-10-09"));
        assertEquals("FEATURE_INVALID", assertThrows(FeatureUnavailableException.class, () -> assembler.assemble(order)).getMessage());
        snapshot(List.of("2", "6", "0", Long.toString(now.toEpochMilli()), "2026-10-08"));
        assertEquals("FEATURE_PROFIT_DATE_INVALID", assertThrows(FeatureUnavailableException.class, () -> assembler.assemble(order)).getMessage());
    }
}
