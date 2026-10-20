package com.risk.gateway.rule;

import com.risk.gateway.Models.CompiledRule;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 一次 volatile 发布规则和状态，调用方不会读到半更新列表。 */
public record RuleSnapshot(List<CompiledRule> rules, Map<String, String> errors,
                           Instant lastAttemptAt, Instant lastSuccessAt, String loadError) {
    public RuleSnapshot {
        rules = List.copyOf(rules);
        errors = Map.copyOf(errors);
    }
    public static RuleSnapshot empty() {
        return new RuleSnapshot(List.of(), Map.of(), null, null, "NOT_LOADED");
    }
}
