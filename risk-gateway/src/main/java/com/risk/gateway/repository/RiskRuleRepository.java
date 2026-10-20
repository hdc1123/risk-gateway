package com.risk.gateway.repository;

import com.risk.gateway.Models.Rule;
import java.util.List;
import java.util.Optional;

/** 数据源契约：全量读取用于识别禁用、删除及优先级变更。 */
public interface RiskRuleRepository {
    List<Rule> findEnabled();
    Optional<Rule> findById(String ruleId);
}
