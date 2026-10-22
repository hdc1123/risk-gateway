# 风控接口（第五部分）

本次新增代码未构建、未测试运行；非 local 配置下需数据库、Redis 特征生产者及实现契约的 Adapter 服务端。

## 通用请求头

- `Authorization: Bearer <GATEWAY_TOKEN>`：订单接口。
- `Authorization: Bearer <ADMIN_TOKEN>`：规则测试接口，不能用 gateway token 替代。
- `X-Request-Id`：订单必填，1~128 位字母/数字/`._:-`。
- `X-Trace-Id`：可选，1~64 位相同字符集；不合法或缺失时生成。
- 对外部署必须启用 HTTPS（通常由反向代理终止 TLS），禁止在源码或访问日志中记录 token。

## POST /api/v1/risk/order

请求使用需求中的完整 OrderRequest。参数校验通过后提交到有界风险线程池，随后原子幂等占位、读取特征、求值、路由及调用 Adapter。

- 特征不可用或快照不可用：明确降级默认 B_BOOK，不填造假统计值。
- 单条脚本失败：可采用其他成功规则，保留降级标记。
- 重复/处理中/结果不确定：不转发；已完成重复返回原 ResponseData。
- Adapter 成功：action=ALLOW，仅表达持久化受理，不表示成交。
- Adapter 结果未知：action=UNKNOWN，无 adapterOrderId，禁止自动重投。
- 未发送/明确未执行：action=NOT_EXECUTED。UNKNOWN/NOT_EXECUTED 是执行状态，不是 LIMIT/REJECT/FREEZE 风控动作。
- Adapter 明确拒绝仍保留 UNCERTAIN 占位，交由核对处理；不自动释放重试。
- Adapter 已受理但 Redis 写完成结果失败：响应保留 adapterOrderId，返回 1504，禁止通过新订单号重投。
- 返回 latencyMs 从过滤器入口计时，包含解析、排队至 Adapter 回应，不包含响应网络传输；当前成功数据在 Redis 完成写入前构造，因此不含完成写入耗时，不能作为完整端到端验收指标。后续监控需记录整个请求。
- 获得幂等占位后的处理结果已接入异步决策日志；重复请求、占位失败、参数/鉴权失败不生成新的决策日志。队列满或写入失败不会改变交易结果，需监控丢失指标。

## POST /api/v1/admin/rules/{ruleId}/test

```json
{"features":{"symbol":"EURUSD","volume":1.0,"side":"BUY","tradesIn1Min":2,"tradesIn5Min":6,"dailyProfit":150.0},"profile":null}
```

读取指定规则（含禁用规则），独立编译缓存、独立管理请求线程池及脚本线程池；不修改生产规则快照、不调用 Adapter。共享 JVM CPU/内存仍有影响，运营接口应做网络隔离和限流。

返回 ScriptResult，包括 ruleId、scriptMd5、returnValue、result、latencyMs、compiled、error。编译失败时 error=COMPILE_FAILED；执行超时等由 error 指示，接口成功不意味着规则命中。MVP1 profile 仅允许 null。

## 规则管理（新增）

全部使用 `ADMIN_TOKEN`，请求在独立有界 `adminExecutor` 执行。部署前执行 `db/005_rule_management.sql`；已有规则会导入为草稿和历史版本，不会自动启用。

| 方法 | 路径（前缀 `/api/v1/admin/rules`） | 功能 |
|---|---|---|
| GET | `/{id}/draft` | 读取草稿及 revision |
| PUT | `/{id}/draft` | 创建或编辑草稿，不影响线上 |
| POST | `/{id}/publish` | 编译验证后发布新版本 |
| GET | `/{id}/state` | 读取当前生产 version 和启停状态（未发布为 version=0） |
| PUT | `/{id}/state` | 启停已发布规则 |
| POST | `/{id}/rollback` | 从历史内容生成新版本，保留启停状态 |
| GET | `/{id}/versions?limit=20` | 最近版本元数据，limit 1~100 |
| GET | `/{id}/audit?limit=20` | 最近操作审计，limit 1~100 |

创建草稿（首次 expectedRevision=0；编辑时传读取到的 revision）：

```json
{"expectedRevision":0,"ruleName":"大额订单","priority":100,"scriptContent":"if (features.volume >= 10) return 'ROUTE_A'; return null","timeoutMs":0,"reason":"创建规则"}
```

发布：

```json
{"expectedRevision":1,"expectedVersion":0,"reason":"验证后发布"}
```

首次发布返回 `{"version":1,"enabled":false}`，再启用：

```json
{"expectedVersion":1,"enabled":true,"reason":"启用规则"}
```

启停也推进版本（上述返回 version=2），避免旧启停请求覆盖新状态。后续发布须带当前生产版本与草稿 revision。回滚请求：

```json
{"expectedVersion":2,"targetVersion":1,"reason":"恢复历史脚本"}
```

- 同规则操作通过数据库行锁串行化，期望版本不匹配返回 1409 / HTTP 409。
- 发布、回滚和启用必须通过编译检查；失败返回 1400 / HTTP 400，事务不改变生产规则及历史。
- 草稿可以暂存语法不完整的脚本，但仍执行大小和基础安全检查。
- 规则、不可变历史和审计写入同一事务。审计 actor 固定为 `admin-token`，表示通过管理员凭证认证；当前共享 token 不能区分真实操作人，不信任客户端自报姓名。需要个人审计应对接独立身份认证。
- 发布后通过原有轮询刷新生效，不在数据库事务内刷新本机缓存；多实例在各自下一次轮询生效，不是集群原子切换。
- 回滚不覆盖草稿，下一次发布前应确认草稿内容。版本历史返回元数据，脚本恢复通过回滚完成。
- 当前不提供物理删除、审批或灰度发布；停用代替删除，避免破坏历史。
- 生产规则请仅通过管理 API 更新；直接 SQL 绕过行锁/版本/审计协议不受并发保护。

## 错误码

| code | 含义 |
|---|---|
| 0 | 已受理成功 / 管理测试已完成 |
| 1001 | 请求参数不合法 |
| 1002 | 幂等重复、处理中或结果不确定；message 区分状态 |
| 1003 | 请求内容/请求ID冲突 |
| 1401 | 无访问权限，HTTP 401 |
| 1400 | 规则编译或安全校验失败，HTTP 400 |
| 1404 | 规则或版本不存在，HTTP 404 |
| 1409 | 草稿或生产版本冲突，HTTP 409 |
| 1500 | 内部错误 |
| 1503 | 线程池过载或幂等设施不可用，HTTP 503 |
| 1504 | Adapter 或幂等写入结果不确定 |
| 1505 | Adapter 明确未受理 |

1001/1002 来自需求，其余为本项目约定。业务状态由 HTTP 200 内 code 表达，参数/鉴权/基础设施异常由统一异常处理返回对应 HTTP 状态。客户端必须解析 code，不得仅根据 HTTP 200 认定成功。

local profile 不创建订单/管理业务接口（仅提供无外部依赖的上下文），默认空 token 拒绝所有受保护 API。Actuator 不由此 token filter 保护，应由部署网络策略保护 metrics 等管理端点。
