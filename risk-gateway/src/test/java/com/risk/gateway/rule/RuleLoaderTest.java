package com.risk.gateway.rule;

import com.risk.gateway.Models.Rule;
import com.risk.gateway.config.RuleConfiguration;
import com.risk.gateway.engine.GroovyRuleCompiler;
import com.risk.gateway.engine.ScriptGuard;
import com.risk.gateway.repository.RiskRuleRepository;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleLoaderTest {
    private final RiskRuleRepository repository = mock(RiskRuleRepository.class);
    private final RuleConfiguration.Properties properties = new RuleConfiguration.Properties(30, 1000, 1000, 32768, 2, 120);
    private final RuleLoader loader = new RuleLoader(repository, new GroovyRuleCompiler(new ScriptGuard(), properties), properties);
    private Rule rule(String id, int priority, String script) {
        return new Rule(id, id, priority, script, null, "ROUTE", 2);
    }

    @Test
    void sortsRulesAndRemovesDisabledOrDeletedRules() {
        when(repository.findEnabled()).thenReturn(List.of(rule("B", 10, "return null"), rule("A", 100, "return 'ROUTE_A'")));
        loader.refresh();
        assertEquals(List.of("A", "B"), loader.snapshot().rules().stream().map(r -> r.rule().ruleId()).toList());
        assertThrows(UnsupportedOperationException.class, () -> loader.snapshot().rules().clear());
        when(repository.findEnabled()).thenReturn(List.of());
        loader.refresh();
        assertTrue(loader.snapshot().rules().isEmpty());
        assertTrue(loader.isUsable()); // 已成功读取的空规则集合法，后续默认路由。
    }

    @Test
    void databaseFailurePreservesLastGoodSnapshot() {
        when(repository.findEnabled()).thenReturn(List.of(rule("A", 1, "return null")));
        loader.refresh();
        var previous = loader.snapshot();
        when(repository.findEnabled()).thenThrow(new IllegalStateException("db unavailable"));
        loader.refresh();
        assertEquals(previous.rules(), loader.snapshot().rules());
        assertEquals(previous.lastSuccessAt(), loader.snapshot().lastSuccessAt());
        assertNotNull(loader.snapshot().loadError());
    }

    @Test
    void invalidRuleDoesNotKeepItsOldScript() {
        when(repository.findEnabled()).thenReturn(List.of(rule("A", 1, "return 'ROUTE_A'")));
        loader.refresh();
        when(repository.findEnabled()).thenReturn(List.of(rule("A", 1, "if (")));
        loader.refresh();
        assertTrue(loader.snapshot().rules().isEmpty());
        assertTrue(loader.snapshot().errors().containsKey("A"));
        assertFalse(loader.isUsable());
    }

    @Test
    void failureBeforeFirstLoadIsNotUsable() {
        when(repository.findEnabled()).thenThrow(new IllegalStateException("missing schema"));
        loader.refresh();
        assertFalse(loader.isUsable());
    }
}
