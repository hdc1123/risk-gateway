package com.risk.gateway.engine;

import com.risk.gateway.Models.*;
import com.risk.gateway.rule.RuleLoader;
import com.risk.gateway.rule.RuleSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "risk.rules.enabled", havingValue = "true", matchIfMissing = true)
public class GroovyRuleEngine {
    private final RuleLoader loader;
    private final ScriptRunner runner;
    private final long budgetNanos;

    public GroovyRuleEngine(RuleLoader loader, ScriptRunner runner,
                           @Value("${risk.rules.evaluation-budget-ms:2}") int budgetMs) {
        if (budgetMs < 1 || budgetMs > 1000) throw new IllegalArgumentException("整轮规则预算必须为 1~1000ms");
        this.loader = loader;
        this.runner = runner;
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMs);
    }

    public Evaluation evaluate(Map<String, Object> features) {
        // 每个订单只读取一次快照，避免刷新时状态与规则列表不一致。
        RuleSnapshot snapshot = loader.snapshot();
        long start = System.nanoTime();
        if (!loader.isUsable(snapshot)) {
            return new Evaluation(List.of(new ScriptResult("__engine__", null, null, null, 0, false,
                "RULE_SNAPSHOT_UNAVAILABLE")), elapsed(start), true);
        }
        Map<String, Object> safeFeatures = immutableFeatures(features);
        List<ScriptResult> results = new ArrayList<>();
        boolean degraded = snapshot.loadError() != null;
        for (CompiledRule rule : snapshot.rules()) {
            long remaining = budgetNanos - (System.nanoTime() - start);
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
                results.add(new ScriptResult(rule.rule().ruleId(), rule.digest(), null, null, 0, true,
                    Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "BUDGET_EXCEEDED"));
                degraded = true;
                break;
            }
            ScriptResult result = runner.execute(rule, safeFeatures, remaining);
            results.add(result);
            degraded |= result.error() != null;
        }
        return new Evaluation(results, elapsed(start), degraded);
    }

    /** MVP1 特征仅允许标量，禁止把可变对象或任意服务实例暴露给脚本。 */
    public static Map<String, Object> immutableFeatures(Map<String, Object> features) {
        Objects.requireNonNull(features, "features");
        Map<String, Object> copy = new LinkedHashMap<>();
        features.forEach((key, value) -> {
            Objects.requireNonNull(key, "feature key");
            if (value != null && !(value instanceof String || value instanceof Boolean
                || value instanceof Long || value instanceof Integer || value instanceof Double
                || value instanceof java.math.BigDecimal))
                throw new IllegalArgumentException("特征必须是支持的标量: " + key);
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
