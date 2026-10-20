package com.risk.gateway.engine;

import com.risk.gateway.Models.Rule;
import com.risk.gateway.config.RuleConfiguration;
import groovy.lang.Binding;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GroovyRuleCompilerTest {
    private final GroovyRuleCompiler compiler = new GroovyRuleCompiler(new ScriptGuard(),
        new RuleConfiguration.Properties(30, 1000, 1000, 32768, 2, 120));

    private Rule rule(String source, String md5) {
        return new Rule("TEST", "测试", 100, source, md5, "ROUTE", 2);
    }

    @Test
    void cachesByActualContentAndSupportsHelperFunctions() throws Exception {
        String source = "def profitable() { features.dailyProfit > 0 }; if (profitable()) return 'ROUTE_A'; return null";
        var first = compiler.compile(rule(source, null));
        var second = compiler.compile(rule(source, null));
        assertSame(first.scriptClass(), second.scriptClass());
        var script = first.scriptClass().getDeclaredConstructor().newInstance();
        script.setBinding(new Binding(Map.of("features", Map.of("dailyProfit", 150))));
        assertEquals("ROUTE_A", script.run());
        assertNotSame(first.scriptClass(), compiler.compile(rule("return 'ROUTE_B'", null)).scriptClass());
    }

    @Test
    void rejectsWrongDigestAndUnsupportedReturnType() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(rule("return null", "bad")));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(
            new Rule("TEST", "测试", 1, "return true", null, "BOOLEAN", 2)));
    }

    @Test
    void blocksDangerousCodeAndAnnotations() {
        assertThrows(SecurityException.class, () -> compiler.compile(rule("System.exit(0)", null)));
        assertThrows(SecurityException.class, () -> compiler.compile(rule("@Grab('x:y:1')\nreturn null", null)));
        assertThrows(SecurityException.class, () -> compiler.compile(rule("import java.io.File\nreturn null", null)));
    }

    @Test
    void zeroTimeoutInheritsDefaultAndExplicitValueWins() {
        assertEquals(2, compiler.compile(new Rule("INHERIT", "测试", 1, "return null", null, "ROUTE", 0)).rule().timeoutMs());
        assertEquals(10, compiler.compile(new Rule("EXPLICIT", "测试", 1, "return null", null, "ROUTE", 10)).rule().timeoutMs());
    }

    @Test
    void enforcesSizeAndCompilationErrors() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(rule("x".repeat(32769), null)));
        assertThrows(RuntimeException.class, () -> compiler.compile(rule("if (", null)));
    }
}
