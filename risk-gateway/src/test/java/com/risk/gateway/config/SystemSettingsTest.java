package com.risk.gateway.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SystemSettingsTest {
    private Map<String,String> values() {
        return new HashMap<>(Map.of("rule.refresh.interval.seconds", "30", "rule.script.timeout.ms", "2",
            "rule.script.cache.size", "1000", "rule.script.sandbox.enabled", "true", "adapter.timeout.ms", "3",
            "adapter.default.book", "B_BOOK", "thread.pool.core.size", "8", "thread.pool.max.size", "16"));
    }
    @Test void parsesCompleteSettings() { assertEquals(SystemSettings.defaults(), SystemSettings.parse(values())); }
    @Test void rejectsInvalidOrMissingSettings() {
        var map=values(); map.remove("adapter.timeout.ms");
        assertThrows(IllegalArgumentException.class, () -> SystemSettings.parse(map));
        map.put("adapter.timeout.ms", "0");
        assertThrows(IllegalArgumentException.class, () -> SystemSettings.parse(map));
    }
    @Test void rejectsSandboxDisableAndInvalidPoolRange() {
        var map=values(); map.put("rule.script.sandbox.enabled", "false");
        assertThrows(IllegalArgumentException.class, () -> SystemSettings.parse(map));
        map.put("rule.script.sandbox.enabled", "true"); map.put("thread.pool.core.size", "17");
        assertThrows(IllegalArgumentException.class, () -> SystemSettings.parse(map));
    }
}
