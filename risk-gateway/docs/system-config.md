# 系统配置应用范围与生产启动检查

## 加载机制

默认启动从 system_config 读取八个 MVP1 配置，完整校验值、范围和类型。缺失、无效或数据库不可用时阻止启动，不能用默认值悄悄覆盖错误生产配置。local profile 禁用加载，使用内置默认值，仅用于无外部依赖上下文。

每 30 秒全量读取一次，只有整份有效才原子发布；刷新失败保留旧配置，并在 systemConfig 健康项报告 DOWN。读取配置不会访问订单热路径数据库。

## 当前生效范围

| 数据库键 | 应用方式 |
|---|---|
| adapter.timeout.ms | 下一次 Adapter 调用读取，调用内固定该值 |
| adapter.default.book | 下一次无命中正常决策生效；异常无命中仍固定 B_BOOK |
| thread.pool.core.size | 启动时创建 riskExecutor 使用；修改后需要重启 |
| thread.pool.max.size | 启动时创建 riskExecutor 使用；修改后需要重启 |
| rule.script.sandbox.enabled | 强制 true；false 整份配置被拒绝，不允许关闭基础检查 |
| rule.refresh.interval.seconds | 动态应用；1 秒轻量检查，按有效间隔进行数据库规则轮询 |
| rule.script.timeout.ms | timeout_ms=0 的规则在下次规则刷新时继承；正数规则值优先 |
| rule.script.cache.size | 启动创建生产/管理独立缓存使用；修改后需要重启 |

线程池和缓存变更在健康检查报告 restartRequired，但不会仅因等待重启而 DOWN。数据库系统配置关闭时规则参数回退到 risk.rules.*。配置轮询自身间隔由 risk.system-config.refresh-ms 启动参数控制。adapter-timeout-ms YAML 参数已移除，避免双来源歧义。

新版 risk_rule.timeout_ms 默认 0，表示继承；已有值（如 2）保持显式配置。旧库需审核执行 004_rule_timeout_inheritance.sql 扩展 CHECK 和默认值，不自动改写现有规则。默认超时变更需要等待系统配置轮询和下一次规则轮询，管理测试则下一次编译读取。整轮规则预算仍由 risk.rules.evaluation-budget-ms 控制，不因单条默认超时变更自动扩大。

规则轮询间隔不应超过 risk.rules.max-staleness-seconds，否则规则可能在两次刷新之间被判定过期；部署时必须匹配配置。

## prod 启动检查

- 禁止 prod 与 local/test 混用。
- 禁止关闭规则、Redis、数据库配置组件。
- 强制 TLS gRPC，禁止 mock。
- gateway/admin/adapter token 至少 32 字符，gateway 与 admin 必须不同。
- 校验规则表、日志模板 log_key 和新增脚本日志列可查询。
- Redis PING 检查连接；不证明 Lua ACL、持久化或故障切换安全。

检查由 ApplicationRunner 执行，在应用就绪之前失败退出；容器/负载均衡必须使用 readiness 探针，不得仅以端口开放判断可接流量。当前不检测真实 Adapter 协议兼容性和受理能力，仍需上线联调。

prod 配置已加入 readinessState/systemConfig/rules/db/redis：`/actuator/health/readiness`。存活检查 `/actuator/health/liveness` 仅检查 livenessState，避免数据库故障导致不停重启。由部署网络保护 Actuator；就绪探针不覆盖 Adapter 可用性。token 通过密钥管理系统提供，勿在日志打印配置内容。

请求耗时指标 risk.http.request.duration 覆盖过滤器进入至同步响应返回或异步完成，包括幂等完成写入与响应序列化。endpoint 仅使用 order/rule_test/other，避免 traceId、订单号等高基数标签。另有 risk.http.async.timeout/error 计数。该服务端计时不含客户端网络 RTT，5ms 直方图边界不是 SLA 验收证明。

本阶段未编译、测试或启动；配置刷新、健康变化、生产检查失败路径仍需集成验证。
