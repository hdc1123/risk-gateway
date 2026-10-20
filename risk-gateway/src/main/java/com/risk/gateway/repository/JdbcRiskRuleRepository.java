package com.risk.gateway.repository;

import com.risk.gateway.Models.Rule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "risk.rules.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcRiskRuleRepository implements RiskRuleRepository {
    private static final String COLUMNS = "rule_id, rule_name, priority, script_content, script_md5, return_type, timeout_ms";
    private static final RowMapper<Rule> MAPPER = (rs, index) -> new Rule(
        rs.getString("rule_id"), rs.getString("rule_name"), rs.getInt("priority"),
        rs.getString("script_content"), rs.getString("script_md5"),
        rs.getString("return_type"), rs.getInt("timeout_ms"));
    private final JdbcTemplate jdbc;

    public JdbcRiskRuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Rule> findEnabled() {
        return jdbc.query("SELECT " + COLUMNS + " FROM risk_rule WHERE enabled = 1 ORDER BY priority DESC, rule_id ASC", MAPPER);
    }

    @Override
    public Optional<Rule> findById(String ruleId) {
        // 管理测试需要读取禁用规则；生产快照只调用 findEnabled。
        return jdbc.query("SELECT " + COLUMNS + " FROM risk_rule WHERE rule_id = ?", MAPPER, ruleId).stream().findFirst();
    }
}
