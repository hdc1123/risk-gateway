-- 仅初始化缺失配置，不覆盖运营已设置的值。
INSERT INTO system_config (config_key, config_value, config_type, description) VALUES
('rule.refresh.interval.seconds', '30', 'INT', '规则轮询间隔'),
('rule.script.timeout.ms', '2', 'INT', '脚本执行默认超时'),
('rule.script.cache.size', '1000', 'INT', '脚本编译缓存大小'),
('rule.script.sandbox.enabled', 'true', 'BOOL', '是否启用沙箱'),
('adapter.timeout.ms', '3', 'INT', 'MT5 Adapter 超时'),
('adapter.default.book', 'B_BOOK', 'STRING', '默认路由'),
('thread.pool.core.size', '8', 'INT', '风控线程池核心数'),
('thread.pool.max.size', '16', 'INT', '风控线程池最大数')
ON DUPLICATE KEY UPDATE config_key = VALUES(config_key);

-- 示例规则默认禁用；确认后由运营手动启用，避免初始化即影响真实订单。
SET @scalp_script = 'def trades5min = features.tradesIn5Min\ndef dailyProfit = features.dailyProfit\nif (trades5min >= 5 && dailyProfit > 0) {\n    return "ROUTE_A"\n}\nreturn null';
SET @large_script = 'if (features.volume >= 10) {\n    return "ROUTE_B"\n}\nreturn null';
INSERT INTO risk_rule
(rule_id, rule_name, priority, enabled, script_content, script_md5, return_type, description)
VALUES
('SCALP_001', '高频剥头皮检测', 100, 0, @scalp_script, MD5(@scalp_script), 'ROUTE', '检测高频盈利行为，路由 A-book'),
('LARGE_ORDER_001', '大额订单监控', 90, 0, @large_script, MD5(@large_script), 'ROUTE', '大额订单路由 B-book')
ON DUPLICATE KEY UPDATE rule_id = VALUES(rule_id);
