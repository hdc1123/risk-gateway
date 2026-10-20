package com.risk.gateway.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.*;
import java.time.Instant;
import java.util.*;

/** 首次加载失败阻止启动；刷新无效则保留上一份完整配置，不发布半更新。 */
@Component
public class SystemConfigService {
    private static final Logger log = LoggerFactory.getLogger(SystemConfigService.class);
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final boolean enabled;
    private volatile SystemSettings current = SystemSettings.defaults();
    private volatile SystemSettings startup = current;
    private volatile String error;
    private volatile Instant lastSuccess;

    public SystemConfigService(ObjectProvider<JdbcTemplate> jdbcProvider,
                               @Value("${risk.system-config.enabled:true}") boolean enabled) {
        this.jdbcProvider=jdbcProvider; this.enabled=enabled;
    }
    @PostConstruct
    public void init() {
        if (!enabled) return;
        current=load(); startup=current; lastSuccess=Instant.now();
    }
    private SystemSettings load() {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null) throw new IllegalStateException("系统配置启用但没有数据库");
        Map<String,String> values = new HashMap<>();
        jdbc.query("SELECT config_key, config_value, config_type FROM system_config", rs -> {
            String key=rs.getString("config_key");
            String expected = key.equals("rule.script.sandbox.enabled") ? "BOOL"
                : key.equals("adapter.default.book") ? "STRING" : "INT";
            if (KEYS.contains(key)) {
                if (!expected.equals(rs.getString("config_type"))) throw new IllegalArgumentException("系统配置类型错误: " + key);
                values.put(key, rs.getString("config_value"));
            }
        });
        return SystemSettings.parse(values);
    }
    private static final Set<String> KEYS=Set.of("rule.refresh.interval.seconds", "rule.script.timeout.ms",
        "rule.script.cache.size", "rule.script.sandbox.enabled", "adapter.timeout.ms", "adapter.default.book",
        "thread.pool.core.size", "thread.pool.max.size");

    @Scheduled(fixedDelayString="${risk.system-config.refresh-ms:30000}")
    public synchronized void refresh() {
        if (!enabled) return;
        try {
            SystemSettings next=load();
            current=next; lastSuccess=Instant.now(); error=null;
        } catch (RuntimeException e) {
            error=e.getClass().getSimpleName();
            log.error("系统配置刷新失败，保留上次有效值", e);
        }
    }
    public SystemSettings current() { return current; }
    public SystemSettings startup() { return startup; }
    public String error() { return error; }
    public Instant lastSuccess() { return lastSuccess; }
    public boolean enabled() { return enabled; }
}
