-- 仅供已执行旧版 001_schema.sql 的数据库；新版新建库无需执行。
-- 执行前确认 template 不含 log_key；本迁移不可重复执行。
ALTER TABLE decision_log_template ADD COLUMN log_key VARCHAR(36) NOT NULL COMMENT '日志重试幂等键' AFTER id,
    ADD UNIQUE KEY uk_log_key (log_key);
-- 若已有按日业务表，需要逐表增加 nullable log_key、为历史行回填 UUID、改为 NOT NULL 并加唯一索引。
-- 不自动扫描/修改历史表，必须经 DBA 评估锁表影响后执行。
