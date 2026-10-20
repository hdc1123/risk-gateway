package com.risk.gateway.engine;

import com.risk.gateway.Models.*;
import com.risk.gateway.rule.RuleLoader;
import com.risk.gateway.rule.RuleSnapshot;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroovyRuleEngineTest {
    @Test
    void unavailableSnapshotDegradesWithoutExecuting() {
        RuleLoader loader = mock(RuleLoader.class);
        ScriptRunner runner = mock(ScriptRunner.class);
        when(loader.snapshot()).thenReturn(RuleSnapshot.empty());
        var result = new GroovyRuleEngine(loader, runner, 1000).evaluate(Map.of());
        assertTrue(result.degraded());
        assertEquals("RULE_SNAPSHOT_UNAVAILABLE", result.scripts().get(0).error());
        verifyNoInteractions(runner);
    }
    @Test
    void failedRuleDoesNotPreventFollowingRule() {
        RuleLoader loader = mock(RuleLoader.class);
        ScriptRunner runner = mock(ScriptRunner.class);
        var a = new CompiledRule(new Rule("A", "A", 100, "", null, "ROUTE", 1000), ScriptRunnerTest.Hit.class, "a");
        var b = new CompiledRule(new Rule("B", "B", 10, "", null, "ROUTE", 1000), ScriptRunnerTest.Hit.class, "b");
        var snapshot = new RuleSnapshot(List.of(a, b), Map.of(), Instant.now(), Instant.now(), null);
        when(loader.snapshot()).thenReturn(snapshot);
        when(loader.isUsable(snapshot)).thenReturn(true);
        when(runner.execute(eq(a), anyMap(), anyLong())).thenReturn(new ScriptResult("A", "a", null, null, 0.1, true, "SCRIPT_ERROR"));
        when(runner.execute(eq(b), anyMap(), anyLong())).thenReturn(new ScriptResult("B", "b", "ROUTE_A", new RuleResult("B", "B", 10, "ROUTE", "book_type", Book.A_BOOK), 0.1, true, null));
        var result = new GroovyRuleEngine(loader, runner, 1000).evaluate(Map.of("volume", 1));
        assertTrue(result.degraded());
        assertEquals(1, result.hits().size());
        assertEquals("B", result.hits().get(0).ruleId());
    }
    @Test
    void featuresAreReadOnlyAndRejectNestedMutableObjects() {
        var features = GroovyRuleEngine.immutableFeatures(Map.of("volume", 1));
        assertThrows(UnsupportedOperationException.class, () -> features.put("volume", 2));
        assertThrows(IllegalArgumentException.class, () -> GroovyRuleEngine.immutableFeatures(Map.of("extra", List.of(1))));
    }
}
