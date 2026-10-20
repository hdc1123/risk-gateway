package com.risk.gateway.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.*;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

/** prod 失败关闭；不以通道创建成功冒充真实 Adapter 兼容性/可用性验收。 */
@Component
public class ProductionStartupCheck implements ApplicationRunner {
    private final Environment env;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<StringRedisTemplate> redis;
    public ProductionStartupCheck(Environment env, ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<StringRedisTemplate> redis) {
        this.env=env; this.jdbc=jdbc; this.redis=redis;
    }
    @Override public void run(ApplicationArguments args) {
        if (!env.acceptsProfiles(Profiles.of("prod"))) return;
        if (env.acceptsProfiles(Profiles.of("local", "test"))) throw new IllegalStateException("prod 禁止混用 local/test");
        for (String key : new String[]{"risk.rules.enabled", "risk.redis.enabled", "risk.system-config.enabled"}) {
            if (!env.getProperty(key, Boolean.class, true)) throw new IllegalStateException("生产组件不能关闭: " + key);
        }
        if (!"grpc".equals(env.getProperty("risk.adapter-mode")) || env.getProperty("risk.adapter-plaintext", Boolean.class, false))
            throw new IllegalStateException("生产 Adapter 必须使用 TLS gRPC");
        String gateway=env.getProperty("risk.gateway-token", "");
        String admin=env.getProperty("risk.admin-token", "");
        String adapter=env.getProperty("risk.adapter-token", "");
        if (gateway.length()<32 || admin.length()<32 || adapter.length()<32 || gateway.equals(admin))
            throw new IllegalStateException("生产 token 至少 32 字符，网关与管理员 token 不可相同");
        JdbcTemplate template=jdbc.getObject();
        template.queryForList("SELECT rule_id FROM risk_rule LIMIT 0");
        template.queryForList("SELECT log_key, script_results, script_latency_ms FROM decision_log_template LIMIT 0");
        // 只读检查连接；Lua ACL 与持久化配置还需部署环境验证。
        redis.getObject().execute((org.springframework.data.redis.core.RedisCallback<String>) connection -> connection.ping());
    }
}
