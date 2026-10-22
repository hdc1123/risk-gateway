-- MySQL 8；先执行 001_schema.sql。管理草稿与生产规则分离，编辑不会改变线上快照。
CREATE TABLE IF NOT EXISTS risk_rule_draft (
    rule_id VARCHAR(64) NOT NULL PRIMARY KEY,
    rule_name VARCHAR(128) NOT NULL,
    priority INT NOT NULL,
    script_content TEXT NOT NULL,
    timeout_ms INT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 1,
    updated_by VARCHAR(128) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_draft_timeout CHECK (timeout_ms BETWEEN 0 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 一次发布对应一个不可变版本；回滚也是新版本，不覆盖历史。
CREATE TABLE IF NOT EXISTS risk_rule_release (
    rule_id VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    priority INT NOT NULL,
    script_content TEXT NOT NULL,
    script_md5 VARCHAR(32) NOT NULL,
    timeout_ms INT NOT NULL,
    published_by VARCHAR(128) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    published_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (rule_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS risk_rule_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    rule_id VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    revision INT NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(512) NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_rule_audit (rule_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 导入原有规则。重复执行不会覆盖管理草稿或历史版本。
INSERT INTO risk_rule_draft (rule_id, rule_name, priority, script_content, timeout_ms, revision, updated_by)
SELECT rule_id, rule_name, priority, script_content, timeout_ms, 1, 'migration'
FROM risk_rule WHERE return_type = 'ROUTE'
ON DUPLICATE KEY UPDATE rule_id = VALUES(rule_id);

INSERT INTO risk_rule_release
(rule_id, version, rule_name, priority, script_content, script_md5, timeout_ms, published_by, reason)
SELECT rule_id, script_version, rule_name, priority, script_content, MD5(script_content), timeout_ms,
       'migration', '导入既有规则；仍须验证脚本'
FROM risk_rule WHERE return_type = 'ROUTE'
ON DUPLICATE KEY UPDATE rule_id = VALUES(rule_id);
