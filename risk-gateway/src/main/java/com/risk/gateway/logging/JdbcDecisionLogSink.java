package com.risk.gateway.logging;

import com.risk.gateway.Models.DecisionContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.github.benmanes.caffeine.cache.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.math.*;
import java.sql.Timestamp;

@Repository
@ConditionalOnProperty(name="risk.rules.enabled", havingValue="true", matchIfMissing=true)
public class JdbcDecisionLogSink implements DecisionLogSink {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Cache<String, Boolean> tables = Caffeine.newBuilder().maximumSize(8).build();
    public JdbcDecisionLogSink(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public static String tableName(long timestamp) {
        LocalDate date = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
        if (date.getYear() < 2000 || date.getYear() > 9999) throw new IllegalArgumentException("日志日期超限");
        return "decision_log_" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    @Override
    public void write(String logKey, DecisionContext context) {
        var order = context.order();
        var response = context.response();
        String table = tableName(response.timestamp()); // 服务端处理时间，不信任订单时间戳。
        tables.get(table, name -> {
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + name + " LIKE decision_log_template");
            return true;
        });
        // log_key 在重试时保持不变，解决写入成功但客户端未收到确认后的重复日志。
        String sql = "INSERT INTO " + table + " (log_key,event_id,trace_id,user_id,mt5_account,order_id,symbol,volume,side,"
            + "features,rules_triggered,script_results,decision,book_type,latency_ms,script_latency_ms,degrade_flag,degrade_reason,created_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE log_key=VALUES(log_key)";
        jdbc.update(sql, logKey, order.eventId(), context.traceId(), order.userId(), order.mt5Account(),
            order.orderId(), order.symbol(), order.volume(), order.side(), json(context.features()),
            json(context.evaluation().hits()), json(context.evaluation().scripts()), json(response),
            context.decision().bookType().name(), decimal(response.latencyMs()), decimal(response.scriptLatencyMs()),
            response.degradeFlag(), response.degradeReason(), Timestamp.from(Instant.ofEpochMilli(response.timestamp())));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("日志 JSON 序列化失败", e); }
    }
    private BigDecimal decimal(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("日志耗时不合法");
        // DECIMAL(8,3) 上限，长时间故障不应导致日志永久无法写入。
        return BigDecimal.valueOf(Math.min(value, 99999.999)).setScale(3, RoundingMode.HALF_UP);
    }
}
