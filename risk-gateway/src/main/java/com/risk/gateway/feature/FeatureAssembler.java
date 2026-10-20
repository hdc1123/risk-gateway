package com.risk.gateway.feature;

import com.risk.gateway.Models.OrderRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/** 消费外部统计生产者发布的原子快照，不在本模块自行推断成交或计算盈亏。 */
@Component
@ConditionalOnProperty(name = "risk.redis.enabled", havingValue = "true", matchIfMissing = true)
public class FeatureAssembler {
    private static final List<Object> FIELDS = List.of("tradesIn1Min", "tradesIn5Min", "dailyProfit", "updatedAt", "profitDate");
    private final StringRedisTemplate redis;
    private final long maxAgeMs;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public FeatureAssembler(StringRedisTemplate redis, @Value("${risk.features.max-age-ms:5000}") long maxAgeMs) {
        this(redis, maxAgeMs, Clock.systemUTC());
    }

    public FeatureAssembler(StringRedisTemplate redis, long maxAgeMs, Clock clock) {
        if (maxAgeMs < 1) throw new IllegalArgumentException("特征有效期必须为正数");
        this.redis = redis;
        this.maxAgeMs = maxAgeMs;
        this.clock = clock;
    }

    public Map<String, Object> assemble(OrderRequest order) {
        String key = "risk:features:v1:{" + order.userId() + ":" + order.mt5Account() + "}";
        try {
            // 单次 HMGET 比多条 GET Pipeline 更少命令，且读取同一 hash 的一致快照。
            List<Object> values = redis.opsForHash().multiGet(key, FIELDS);
            if (values.size() != FIELDS.size() || values.stream().anyMatch(Objects::isNull))
                throw new FeatureUnavailableException("FEATURE_MISSING");
            long one = Long.parseLong(values.get(0).toString());
            long five = Long.parseLong(values.get(1).toString());
            BigDecimal profit = new BigDecimal(values.get(2).toString());
            long updated = Long.parseLong(values.get(3).toString());
            long now = clock.millis();
            if (updated > now || updated < now - maxAgeMs)
                throw new FeatureUnavailableException("FEATURE_STALE");
            if (!LocalDate.now(clock.withZone(ZoneOffset.UTC)).toString().equals(values.get(4).toString()))
                throw new FeatureUnavailableException("FEATURE_PROFIT_DATE_INVALID");
            if (one < 0 || five < one || profit.precision() > 24 || Math.abs((long) profit.scale()) > 8)
                throw new FeatureUnavailableException("FEATURE_INVALID");
            Map<String, Object> features = new LinkedHashMap<>();
            features.put("symbol", order.symbol());
            features.put("volume", order.volume());
            features.put("side", order.side());
            features.put("tradesIn1Min", one);
            features.put("tradesIn5Min", five);
            features.put("dailyProfit", profit);
            return Collections.unmodifiableMap(features);
        } catch (FeatureUnavailableException e) { throw e; }
        catch (RuntimeException e) { throw new FeatureUnavailableException("FEATURE_READ_FAILED", e); }
    }
}
