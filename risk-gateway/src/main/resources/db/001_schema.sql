-- MySQL 8.x；生产由部署人员执行，不在应用启动时自动修改表结构。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS risk_rule (
    rule_id VARCHAR(64) NOT NULL COMMENT '规则ID',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(32) NOT NULL DEFAULT 'BEHAVIOR',
    priority INT NOT NULL DEFAULT 0,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    script_content TEXT NOT NULL,
    script_version INT NOT NULL DEFAULT 1,
    script_md5 VARCHAR(32) NULL,
    return_type VARCHAR(32) NOT NULL DEFAULT 'ROUTE',
    description VARCHAR(512) NULL,
    timeout_ms INT NOT NULL DEFAULT 0 COMMENT '0继承系统默认；正数为规则独立超时',
    created_by VARCHAR(64) NULL,
    updated_by VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (rule_id),
    KEY idx_enabled_priority (enabled, priority DESC),
    KEY idx_updated_at (updated_at),
    KEY idx_script_md5 (script_md5),
    CONSTRAINT chk_rule_timeout CHECK (timeout_ms BETWEEN 0 AND 1000),
    CONSTRAINT chk_rule_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控规则表';

CREATE TABLE IF NOT EXISTS system_config (
    config_key VARCHAR(128) NOT NULL,
    config_value TEXT NOT NULL,
    config_type VARCHAR(32) NOT NULL DEFAULT 'STRING',
    description VARCHAR(512) NULL,
    updated_by VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置表';

-- 日志结构模板；后续日志模块通过受控日期创建 decision_log_YYYYMMDD。
-- 不把模板当作业务写入表，也不在本阶段预创建固定日期表。
CREATE TABLE IF NOT EXISTS decision_log_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    log_key VARCHAR(36) NOT NULL COMMENT '日志重试幂等键',
    event_id VARCHAR(64) NOT NULL,
    trace_id VARCHAR(64) NULL,
    user_id BIGINT NOT NULL,
    mt5_account BIGINT NULL,
    order_id VARCHAR(64) NULL,
    symbol VARCHAR(32) NULL,
    volume DECIMAL(18,2) NULL,
    side VARCHAR(8) NULL,
    features JSON NULL,
    rules_triggered JSON NULL,
    script_results JSON NULL,
    decision JSON NULL,
    book_type VARCHAR(16) NULL,
    latency_ms DECIMAL(8,3) NULL,
    script_latency_ms DECIMAL(8,3) NULL,
    degrade_flag TINYINT(1) NOT NULL DEFAULT 0,
    degrade_reason VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_log_key (log_key),
    KEY idx_user_time (user_id, created_at),
    KEY idx_event (event_id),
    KEY idx_book_time (book_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='决策日志表结构模板';
