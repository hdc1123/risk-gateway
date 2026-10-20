package com.risk.gateway.config;

import org.springframework.boot.actuate.health.*;
import org.springframework.stereotype.Component;
import java.util.List;

@Component("systemConfig")
public class SystemConfigHealthIndicator implements HealthIndicator {
    private final SystemConfigService configs;
    public SystemConfigHealthIndicator(SystemConfigService configs) { this.configs=configs; }
    @Override public Health health() {
        if (!configs.enabled()) return Health.up().withDetail("mode", "disabled").build();
        var current=configs.current();
        var startup=configs.startup();
        return (configs.error() == null ? Health.up() : Health.down())
            .withDetail("lastSuccessAt", String.valueOf(configs.lastSuccess()))
            .withDetail("error", String.valueOf(configs.error()))
            .withDetail("threadPoolRestartRequired", current.poolCore()!=startup.poolCore() || current.poolMax()!=startup.poolMax())
            .withDetail("scriptCacheRestartRequired", current.scriptCacheSize()!=startup.scriptCacheSize())
            .build();
    }
}
