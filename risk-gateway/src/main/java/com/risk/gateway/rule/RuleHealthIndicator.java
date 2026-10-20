package com.risk.gateway.rule;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("rules")
@ConditionalOnProperty(name = "risk.rules.enabled", havingValue = "true", matchIfMissing = true)
public class RuleHealthIndicator implements HealthIndicator {
    private final RuleLoader loader;
    public RuleHealthIndicator(RuleLoader loader) { this.loader = loader; }
    @Override
    public Health health() {
        RuleSnapshot state = loader.snapshot();
        boolean healthy = loader.isUsable() && state.loadError() == null;
        return (healthy ? Health.up() : Health.down())
            .withDetail("activeRules", state.rules().size())
            .withDetail("invalidRules", state.errors().keySet())
            .withDetail("lastSuccessAt", String.valueOf(state.lastSuccessAt()))
            .withDetail("loadError", String.valueOf(state.loadError())).build();
    }
}
