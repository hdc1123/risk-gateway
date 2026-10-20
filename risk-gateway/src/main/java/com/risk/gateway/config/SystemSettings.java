package com.risk.gateway.config;

import com.risk.gateway.Models.Book;
import java.util.Map;

/** 数据库配置完整校验；线程池和编译缓存大小启动应用，其余按消费组件读取。 */
public record SystemSettings(int refreshSeconds, int scriptTimeoutMs, int scriptCacheSize,
    boolean sandboxEnabled, int adapterTimeoutMs, Book defaultBook, int poolCore, int poolMax) {
    public static SystemSettings defaults() {
        return new SystemSettings(30, 2, 1000, true, 3, Book.B_BOOK, 8, 16);
    }
    public static SystemSettings parse(Map<String, String> values) {
        int refresh = integer(values, "rule.refresh.interval.seconds", 1, 3600);
        int timeout = integer(values, "rule.script.timeout.ms", 1, 1000);
        int cache = integer(values, "rule.script.cache.size", 1, 10000);
        String sandbox = required(values, "rule.script.sandbox.enabled");
        if (!sandbox.equals("true")) throw new IllegalArgumentException("MVP1 不允许关闭基础脚本检查");
        int adapter = integer(values, "adapter.timeout.ms", 1, 1000);
        Book book = Book.valueOf(required(values, "adapter.default.book"));
        int core = integer(values, "thread.pool.core.size", 1, 128);
        int max = integer(values, "thread.pool.max.size", 1, 256);
        if (core > max) throw new IllegalArgumentException("核心线程数不能超过最大线程数");
        return new SystemSettings(refresh, timeout, cache, true, adapter, book, core, max);
    }
    private static String required(Map<String,String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少系统配置: " + key);
        return value.trim();
    }
    private static int integer(Map<String,String> values, String key, int min, int max) {
        int value = Integer.parseInt(required(values, key));
        if (value < min || value > max) throw new IllegalArgumentException("系统配置越界: " + key);
        return value;
    }
}
