# Risk Gateway · MVP1

Spring Boot 风控网关项目骨架。独立于同级 trader-adapter 和 python-mt5-sidecar，不修改既有服务。

## 当前交付范围

已创建启动类、Maven 依赖、请求/响应及规则模型、错误码、独立有界线程池、环境配置、容器文件和上下文测试入口。

**当前处于分步实现阶段，不是完整的生产风控服务。** 第一部分已实现 MySQL 表结构与初始化 SQL、JDBC 规则查询、Groovy 编译缓存、基础脚本安全检查、不可变规则快照、30 秒轮询和规则健康检查。第二部分已实现脚本执行器、规则求值引擎和路由决策。第三部分已实现 Redis 特征快照读取和 Lua 原子幂等状态控制。第四部分已实现 MT5 Adapter 客户端、转发契约和本地 mock。第五部分已接通订单主流程、Bearer 鉴权、统一异常响应和独立规则测试接口。第六部分已接入有界队列异步决策日志、按日表写入、重试与指标。第七部分已实现数据库系统配置加载、部分参数应用及生产启动检查；第八部分已补齐配置应用、请求耗时监控与就绪探针；运维核对能力仍待后续实现；真实 Adapter 服务端需实现新增契约。现已执行本地构建及单元测试，但尚未完成真实外部依赖集成测试及上线验收。

### 第九部分：规则管理与语法级防御（本轮新增）

- 新增草稿读写、编译校验发布、启停、版本历史、回滚及变更审计 API，见 [接口说明](docs/api.md)。
- 部署前执行 `src/main/resources/db/005_rule_management.sql`。既有规则导入草稿和历史；首次发布默认禁用，更新与回滚保留线上启停状态。
- 草稿 revision 与生产 version 分离，行锁和期望版本防止并发覆盖；发布、版本历史与审计在同一数据库事务内提交。启停也推进版本，回滚生成新版本。
- 生产启动门禁新增管理表结构检查。变更仍在下一轮规则刷新后生效，不承诺多实例同一时刻切换。
- 新增 Groovy AST 检查，拒绝动态成员名、敏感属性/方法、显式对象构造、类型引用、静态调用与方法指针；保留基础关键词检查。**这是纵深防御而非完整沙箱**，仍仅支持受信任管理员脚本。
- 新增管理事务回滚、版本冲突、并发更新测试，以及真实 Groovy 无限循环中断后线程恢复测试。测试执行结果以实际构建输出为准。

### 第十部分：旁路、分流与隔离执行基础

- 新增 OFF/SHADOW/CANARY/AB 模式，按账户 SHA-256 稳定分桶，候选完整规则集固定历史版本；独立旁路线程池不调用 Adapter。新增配置审计、分组及旁路差异指标。
- 新增个人管理员凭证及 READER/EDITOR/PUBLISHER 角色；审批绑定草稿 revision，编辑者不可自审，可配置强制审批。审批模式暂禁止实验开启和直接历史回滚，避免绕过审批。
- 新增可选一次性子 JVM 编译/执行，编译墙钟超时、进程终止、容量上限和 JVM 内存上限。**尚非完整 OS 沙箱**，默认关闭，启用后不能承诺 2ms 延迟。
- 部署前继续执行 `006_rollout.sql`、`007_rule_approval.sql`。Docker 改为先运行测试并使用展开 classpath，支持子 JVM worker。
- 完整配置、行为及未完成项目见 [生产化边界与验收清单](docs/production-readiness.md)。非 ROUTE 动作、删除归档、实验归因/审批、OS 沙箱和真实联调/压测/故障演练仍未完成。

### 第一部分使用与边界

- 手动依次执行 `src/main/resources/db/001_schema.sql` 和 `002_seed.sql`；SQL 不随应用启动自动执行。
- 示例规则默认禁用。确认规则后手动执行 `UPDATE risk_rule SET enabled = 1 WHERE rule_id = 'SCALP_001';`。
- 更新脚本时同步更新 `script_md5 = MD5(script_content)`；摘要不一致的规则编译失败，不继续执行其旧版本。
- 每轮全量读取启用规则，识别删除、禁用、优先级和内容变更；同优先级按规则 ID 排序，原子发布不可变快照。
- 真实脚本 SHA-256 用作编译缓存键，MD5 用于数据库内容一致性检查；编译不在订单链路执行。
- 数据库读取失败保留旧快照；超过 `max-staleness-seconds` 或存在无效规则时 `isUsable()` 返回 false，后续网关必须据此降级。读取失败时健康检查立即 DOWN。
- 空规则集可以是合法快照，后续路由模块负责默认 B_BOOK。
- `ScriptGuard` 是保守关键词检查，并非严格隔离沙箱，可能拦截注释或合法名称，也无法阻止所有动态绕过。**仅限受信任管理员写入规则，不得接收不可信脚本**；真正不可信代码需要进程级隔离。
- 编译器注入 `ThreadInterrupt` 协作中断；执行器超时后调用 `cancel(true)`，不能将其视为硬终止机制。
- 规则相关参数仍读取 `risk.rules.*` 启动配置；第七部分已将数据库 Adapter 超时/默认路由动态应用，风险线程池参数启动应用，详细范围见系统配置文档。
- `local` profile 关闭数据库规则加载，不创建规则健康组件。
- 已添加编译器和加载器测试代码，本次未执行测试或构建。

### 第二部分：执行与路由

- `ScriptRunner` 为每次执行创建独立 Script 和 Binding，绑定只读标量特征；`profile` 固定为 null，`ext` 为空。
- 超时包含排队、实例化、绑定及脚本执行。超时、中断或线程池拒绝均记录明确错误码，不在订单线程执行脚本。
- 返回值仅接受字符串 `ROUTE_A`、`ROUTE_B` 或 null；其他类型作为无效结果，不调用任意返回对象的 `toString()`。
- `GroovyRuleEngine` 每次使用同一规则快照；首次未加载、快照过期或含编译失败规则时不执行，返回降级状态。短暂数据库查询失败但快照仍有效时继续执行并标记降级。
- 单条运行失败跳过并继续其他规则；调用线程中断、整轮预算耗尽时停止继续提交。默认整轮预算 `risk.rules.evaluation-budget-ms=2`，单条超时受剩余预算限制。JVM 调度与 GC 仍可能让实际耗时超过预算，这不是硬实时保证。
- `RoutingDecider` 采用最高优先级的成功结果，同优先级按规则 ID 排序；无成功命中默认 B_BOOK，不修改输入列表。单条失败不强制覆盖其他成功规则的路由，网关后续必须保留降级标记。
- 当前 `Evaluation.latencyMs` 为整轮规则求值墙钟耗时，单条 `ScriptResult.latencyMs` 含排队及执行开销，便于后续日志和接口使用。
- 新增执行器、引擎与路由单元测试源代码；尚未执行测试、编译或运行验证。

### 第三部分：Redis 特征与幂等

**特征数据契约**

- 键：`risk:features:v1:{userId:mt5Account}`，类型 Hash。
- 字段：`tradesIn1Min`、`tradesIn5Min` 为非负整数，后者不能小于前者；`dailyProfit` 为十进制金额；`updatedAt` 为 UTC 毫秒时间；`profitDate` 为 UTC 日期 `YYYY-MM-DD`。
- 外部统计服务必须用一条 HSET 原子更新所有字段，并设置合理 TTL；窗口含义为当前时刻之前的订单统计，不含当前待路由订单。外部统计服务尚未在本项目实现，真实数据源需联调。
- 单次 HMGET 读取同一 Hash 快照（代替多命令 Pipeline）。默认有效期 5 秒；缺失、过期、未来时间、跨日盈亏及读取失败均抛出 `FeatureUnavailableException`，不静默填零。
- 日盈亏按 BigDecimal 传入 Groovy，避免浮点金额误差。返回只读特征 Map。

**幂等状态契约**

- `claim(requestId, order)` 原子绑定请求 ID 和业务订单（用户+账户+订单号），返回 ACQUIRED / PROCESSING / COMPLETED / UNCERTAIN / CONFLICT。
- 相同订单使用不同请求 ID 仍会去重；同一账户内请求 ID 被用于不同内容返回 CONFLICT。请求 ID 必须使用字母、数字或 `._:-`，长度 1~128。
- 指纹包含完整 OrderRequest JSON，包括事件 ID 和时间戳。重试必须保持原始请求，Decimal 表达也应保持一致；请求 ID 唯一性范围为用户+账户。
- 只有 ACQUIRED 的 owner token 可更新状态；仅确定未调用 Adapter 时可 `releaseBeforeDispatch`。Adapter 已调用但结果未知时使用 `markUncertain`，不得释放或自动重投。
- PROCESSING / UNCERTAIN 不自动过期，进程崩溃后必须查询 Adapter 核对恢复；这会牺牲可用性以避免时间锁过期造成重复交易。运营不得直接删除不确定订单键。
- COMPLETED 缓存实际 ResponseData，默认保留 24 小时，过期后不再提供去重保证。请求 ID 绑定也有保留期。Adapter 必须以业务订单 ID 实现持久幂等，覆盖长周期重放及 Redis 数据丢失。
- Redis Lua 脚本使用相同账户 hash tag，两个键落在同一 Cluster 槽。Java 边界已添加 mock 测试；Lua 并发、原子性、Cluster 行为仍需真实 Redis 集成测试，本次未执行。
- Redis 应使用独立实例、`noeviction`、持久化、访问控制和告警；异步复制故障切换仍可能丢占位，**本实现不宣称跨 Redis 故障的 exactly-once**。
- Redis 超时可能发生在写入已经成功之后，异常必须阻止转发/重投；后续网关负责将异常映射到响应和日志。
- `local` profile 禁用 Redis 业务组件，不提供虚假的内存幂等替代。

### 第四部分：MT5 Adapter

- 新增 `MT5Adapter`、`GrpcMT5Adapter`、`MockMT5Adapter`、`AdapterProtocol` 和 `AdapterException`。
- A_BOOK/B_BOOK 分别使用可配置 gRPC 通道；异步提交后在默认 3ms 预算内等待，配置 RPC deadline，禁用自动重试。
- 成功响应校验协议版本、业务幂等键、通道和 Adapter 订单号；成功仅代表 Adapter 持久化受理，不代表 MT5 成交。
- 超时、中断、RPC 错误或响应异常按 UNCERTAIN 处理；不会自动改道或重投。REJECTED 仅表示 Adapter 明确未产生交易副作用，不是风控处置动作。
- 默认模式改为 grpc，非 local/test 环境要求访问 token，默认 TLS。mock 仅允许 local/test，不产生真实订单。
- 使用自定义 JSON gRPC marshaller，**不是 protobuf 协议**。现有服务未发现兼容接口，具体契约与服务端必须实现的幂等保证见 [Adapter 对接文档](docs/adapter-contract.md)。
- 新增协议序列化、响应校验和 mock 环境限制测试代码，未执行；尚未完成实际服务端联调。

### 第五部分：订单与管理接口

- `POST /api/v1/risk/order` 接通参数校验、风险线程池、原子幂等、特征读取、Groovy 求值、路由和 Adapter 转发。
- 未完成/不确定重复不调用 Adapter；特征失败明确降级 B_BOOK；Adapter 超时保留占位而非释放重投。Adapter 受理成功但幂等落结果失败返回原 Adapter 订单号和不确定错误码。
- `POST /api/v1/admin/rules/{ruleId}/test` 使用独立缓存、管理请求与脚本线程池，不修改生产快照、不调用 Adapter；管理测试依然共享 JVM 资源，不能承诺完全隔离。
- Bearer token 区分 gateway/admin；空 token 拒绝访问，不匿名开放业务接口。traceId 在请求线程生成并显式传给异步任务。
- 新增订单成功、重复、特征失败、Adapter 超时和过滤器鉴权测试代码，均未执行。
- 接口错误码、执行状态、HTTP 约定及计时范围详见 [接口说明](docs/api.md)。数据库决策日志已由第六部分接入。

### 第六部分：异步决策日志

- 获得幂等占位的订单处理结果（成功、降级、Adapter 异常）经 `DecisionLogger` 非等待入队；重复请求及占位失败不生成新决策日志。
- 单消费者在后台写 MySQL，包含特征、命中规则、逐脚本结果、响应决策和耗时。写入失败默认重试最多 3 次，日志 UUID 在重试中不变。
- 按服务端响应 UTC 日期写 `decision_log_YYYYMMDD`，日期由 LocalDate 格式化，不接受调用方表名。后台首次写入以模板创建表，需要 CREATE 权限；生产建议 DBA 提前建表与归档，DDL 不在订单线程执行。
- 新版模板增加 `log_key` 唯一索引，防止写入已成功但确认丢失时重复落日志。若已执行旧版建表，需审核执行 `003_log_key_migration.sql`；既有按日表需 DBA 单独迁移。
- 指标：`risk.decision.log.accepted`、`written`、`failures`、`dropped`、`queue.size`；队列满直接丢弃并计数，不阻塞或重投交易，必须设置告警。
- 默认关闭等待 5 秒排空；JDBC 调用不一定响应线程中断，需要数据库连接 socket/query 超时及部署终止宽限期配合。中断、重试耗尽、排队遗留均计入丢失指标。
- **该实现是内存队列最佳努力写入，不是审计级持久化**。进程崩溃、强制终止或长期数据库故障会丢日志。如果业务要求不可丢失，必须增加持久化 WAL/消息系统或同步可靠写入并重新评估 <5ms 目标。
- 新增队列溢出、稳定重试键、关闭排空和 UTC 分表测试代码，未执行构建或测试。

### 第七部分：系统配置与生产启动检查

- 启动读取并严格校验 `system_config` 八项配置；缺失、类型错误、越界或加载失败阻止启动。定时刷新失败保留旧值并报告健康异常。
- Adapter 超时和正常未命中的默认路由动态应用，风险线程池核心/最大数启动应用；异常无命中依然安全降级 B_BOOK。
- 沙箱配置不能关闭；规则轮询、脚本默认超时和缓存大小的应用已由第八部分补齐。
- prod 检查 profile 冲突、组件开关、TLS/非 mock、token 强度与角色分离、表结构和 Redis 连接。
- 新增配置校验测试源代码，未执行。生效范围及启动门禁详见 [系统配置说明](docs/system-config.md)。

### 第八部分：配置应用、请求指标与探针

- 数据库规则轮询间隔动态应用；每秒轻量判断，不是每秒查询规则表。
- `timeout_ms=0` 继承系统默认超时，下一规则刷新生效；正数显式值优先。新库默认改为 0，旧库迁移见 `004_rule_timeout_inheritance.sql`。
- 生产/管理编译缓存大小使用启动数据库配置；修改后需要重启，健康项报告待重启状态。
- `RequestMetricsFilter` 记录同步响应或异步完成后的服务端全请求耗时，包含完成写入与序列化；提供 5ms 桶和异步超时/错误计数，不含客户端网络 RTT。
- prod 提供独立 liveness/readiness 配置，readiness 包含数据库、Redis、规则及系统配置健康；不冒充 Adapter 受理能力探测。
- 新增超时继承与同步/异步请求指标测试源代码，未执行。

## 技术基线

- Java 17 / Maven 3.6.3+
- Spring Boot 3.3.13
- Groovy 4、Caffeine、Spring JDBC、MySQL 8、Spring Data Redis、gRPC
- JUnit 5 / Spring Boot Test

依赖版本为项目初始基线，上线前需完成依赖漏洞扫描与版本复核。

## 目录

```text
src/main/java/com/risk/gateway/
  RiskApplication.java          启动入口
  Models.java                   初始 DTO 与领域模型
  common/                       通用错误码
  config/                       线程池等基础设施配置
  controller/                   订单接口、规则测试及统一异常响应
  service/                      订单编排与独立规则测试
  feature/                      Redis 特征快照读取、有效性校验
  engine/                       编译缓存、安全检查、脚本执行器及规则引擎
  rule/                         不可变快照、轮询刷新与健康检查
  routing/                      最高优先级路由决策
  adapter/                      gRPC JSON 契约、A/B 转发客户端、本地 mock
  idempotency/                  Redis 原子占位及状态转换
  logging/                      有界队列、按日日志、重试与指标
  repository/                   JDBC 规则查询
src/main/resources/
  application.yml               公共配置
  application-local.yml         无外部依赖的骨架模式
  application-prod.yml          生产环境变量模板
  db/                           建表及初始配置、示例规则 SQL
src/test/java/                  上下文测试
```

## 后续手动使用

以下仅为使用说明，本次没有执行下载、编译、测试或启动。

准备 Java 17 与 Maven 后，启动不连接 MySQL/Redis 的骨架：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
curl http://localhost:8080/actuator/health
```

测试：`mvn test`。打包：`mvn clean package`。

`compose.yml` 只提供本地 MySQL/Redis，业务表需手动执行上述 SQL 初始化。示例密码禁止用于生产。生产使用 `prod` profile，通过环境变量提供 MySQL、Redis、Adapter 地址和凭证。当前 prod 配置不是生产上线验收证明。

## 实现约束

1. 规则加载与编译在后台执行，订单链路仅访问不可变规则快照。
2. 完整轮询识别新增、修改、禁用和删除；缓存键包含内容摘要。
3. 幂等需原子占位，并明确多实例和 Adapter 执行结果不确定时的策略。
4. 脚本超时不能只依赖 `Future.cancel(true)`；需增加协作中断及安全限制。可信脚本与不可信脚本隔离边界必须明确。
5. Adapter 超时不得盲目改道或重投，避免重复交易。
6. 异步日志必须有队列容量、失败指标和持久化可靠性策略。
7. < 5ms 是待真实环境压测验证的目标，不由骨架或本地 mock 结果保证。
