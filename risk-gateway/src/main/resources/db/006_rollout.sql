-- 执行 005 后执行。本表一次只允许一个全局实验，避免同一请求进入重叠实验。
CREATE TABLE IF NOT EXISTS risk_rollout (
 id INT NOT NULL PRIMARY KEY,
 revision INT NOT NULL DEFAULT 0,
 mode VARCHAR(16) NOT NULL DEFAULT 'OFF',
 experiment_id VARCHAR(64) NOT NULL DEFAULT 'none',
 salt VARCHAR(64) NOT NULL DEFAULT 'initial',
 traffic_bps INT NOT NULL DEFAULT 0,
 candidate_versions JSON NOT NULL,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT chk_rollout_id CHECK (id = 1),
 CONSTRAINT chk_rollout_bps CHECK (traffic_bps BETWEEN 0 AND 10000),
 CONSTRAINT chk_rollout_mode CHECK (mode IN ('OFF','SHADOW','CANARY','AB'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO risk_rollout (id,candidate_versions) VALUES (1,JSON_OBJECT())
ON DUPLICATE KEY UPDATE id=VALUES(id);
