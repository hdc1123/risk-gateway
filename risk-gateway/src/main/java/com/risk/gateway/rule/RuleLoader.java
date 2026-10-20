package com.risk.gateway.rule;

import com.risk.gateway.Models.CompiledRule;
import com.risk.gateway.Models.Rule;
import com.risk.gateway.config.RuleConfiguration;
import com.risk.gateway.engine.GroovyRuleCompiler;
import com.risk.gateway.repository.RiskRuleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

@Component
@ConditionalOnProperty(name = "risk.rules.enabled", havingValue = "true", matchIfMissing = true)
public class RuleLoader {
    private static final Logger log = LoggerFactory.getLogger(RuleLoader.class);
    private final RiskRuleRepository repository;
    private final GroovyRuleCompiler compiler;
    private final RuleConfiguration.Properties properties;
    private volatile RuleSnapshot snapshot = RuleSnapshot.empty();
    private final com.risk.gateway.config.SystemConfigService configs;
    private volatile long lastRefreshNanos;

    public RuleLoader(RiskRuleRepository repository, GroovyRuleCompiler compiler, RuleConfiguration.Properties properties) {
        this(repository, compiler, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RuleLoader(RiskRuleRepository repository, GroovyRuleCompiler compiler, RuleConfiguration.Properties properties,
                      com.risk.gateway.config.SystemConfigService configs) {
        this.configs = configs;
        this.repository = repository;
        this.compiler = compiler;
        this.properties = properties;
    }

    @PostConstruct
    public void init() { refresh(); }

    // 1 秒轻量调度检查，数据库读取仍按有效配置间隔执行，不做规则热加载。
    @Scheduled(fixedDelay = 1000)
    public synchronized void poll() {
        int interval = configs != null && configs.enabled() ? configs.current().refreshSeconds() : properties.refreshIntervalSeconds();
        if (System.nanoTime() - lastRefreshNanos >= java.util.concurrent.TimeUnit.SECONDS.toNanos(interval)) refresh();
    }

    public synchronized void refresh() {
        Instant attempt = Instant.now();
        try {
            List<Rule> rows = repository.findEnabled();
            if (rows.size() > properties.maxActiveRules()) throw new IllegalStateException("启用规则数量超限");
            List<CompiledRule> compiled = new ArrayList<>();
            Map<String, String> errors = new LinkedHashMap<>();
            Set<String> ids = new HashSet<>();
            for (Rule row : rows) {
                if (!ids.add(row.ruleId())) throw new IllegalStateException("重复规则ID");
                try { compiled.add(compiler.compile(row)); }
                catch (RuntimeException e) {
                    // 不沿用该规则的旧脚本，防止禁用或删除后仍执行旧逻辑。
                    errors.put(row.ruleId(), e.getClass().getSimpleName());
                    log.error("规则编译失败 ruleId={}", row.ruleId(), e);
                }
            }
            compiled.sort(Comparator.comparingInt((CompiledRule r) -> r.rule().priority()).reversed()
                .thenComparing(r -> r.rule().ruleId()));
            snapshot = new RuleSnapshot(compiled, errors, attempt, Instant.now(), null);
            log.info("规则刷新完成 active={}, invalid={}", compiled.size(), errors.size());
        } catch (RuntimeException e) {
            RuleSnapshot old = snapshot;
            snapshot = new RuleSnapshot(old.rules(), old.errors(), attempt, old.lastSuccessAt(), e.getClass().getSimpleName());
            log.error("规则读取失败，保留上次快照", e);
        } finally {
            lastRefreshNanos = System.nanoTime();
        }
    }

    public RuleSnapshot snapshot() { return snapshot; }

    public boolean isUsable() {
        return isUsable(snapshot);
    }

    public boolean isUsable(RuleSnapshot current) {
        return current.lastSuccessAt() != null && current.errors().isEmpty()
            && Instant.now().isBefore(current.lastSuccessAt().plusSeconds(properties.maxStalenessSeconds()));
    }
}
