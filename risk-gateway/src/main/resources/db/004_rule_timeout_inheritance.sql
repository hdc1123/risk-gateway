-- 已执行旧版 001 的环境需 DBA 审核执行；不重复执行。
-- 保留已有规则的 timeout_ms=2 等显式值，不自动改为继承。
ALTER TABLE risk_rule DROP CHECK chk_rule_timeout,
    MODIFY COLUMN timeout_ms INT NOT NULL DEFAULT 0 COMMENT '0继承系统默认；正数为规则独立超时',
    ADD CONSTRAINT chk_rule_timeout CHECK (timeout_ms BETWEEN 0 AND 1000);
-- 需要继承的规则由运营选择：UPDATE risk_rule SET timeout_ms=0 WHERE rule_id='...';
