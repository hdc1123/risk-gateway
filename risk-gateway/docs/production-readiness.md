# 生产化实现与验收边界

本项目不能仅凭单元测试通过就认定生产就绪。下列能力已提供代码；真实环境验收仍须部署方执行。

## 旁路、灰度及 A/B

先执行 005、006、007 SQL。`GET/PUT /api/v1/admin/rollout` 使用管理员认证；PUT 例：

```json
{"expectedRevision":0,"mode":"SHADOW","experimentId":"route-v2","salt":"cohort-v1","trafficBps":1000,"candidateVersions":{"SCALP_001":1},"reason":"10% 旁路验证"}
```

- OFF：停止实验。SHADOW：仅选中账户异步评估候选，订单仍走基线，绝不额外调用 Adapter。
- CANARY：选中账户走候选，其余走基线。AB：采用相同稳定分桶机制，control=基线、candidate=候选；不是自动统计显著性分析平台。
- 分桶单位为 `userId:mt5Account`，SHA-256 长度前缀哈希，10000 个桶。相同 experimentId、salt、账户稳定分桶；更改 salt 会重新分配。
- 候选是完整规则集，版本引用不可变 release 表；候选集外规则不会执行。不要把候选误认为单条覆盖补丁。
- 候选异常回退基线且保留降级标记。基线自己不可用时仍按原降级策略处理。
- 配置 5 秒刷新，30 秒过期；读取/编译失败停止实验，不继续分流旧配置。多实例不是原子切换。
- `risk.rollout.assignment` 提供 mode/arm 计数；`risk.rollout.shadow` 提供 same/different/error/dropped 计数。标签有界，不写账户/实验名高基数标签。
- 目前未提供按实验持久化曝光及业务收益归因，不能据此认定完成 A/B 统计验收。
- 开启 `risk.rules.approval-required=true` 后，实验 PUT 仅允许 OFF，防止实验接口绕过审批；实验独立审批工作流尚待实现。

## 个人身份与审批

通过部署密钥配置 `risk.admin-identities-json`：

```json
{"alice":{"token":"独立随机且至少32字符的凭证","role":"EDITOR"},"bob":{"token":"另一独立随机且至少32字符的凭证","role":"PUBLISHER"}}
```

不要使用上述示意字符串，勿提交真实凭证。个人认证配置非空时拒绝原共享 admin token。READER 只读，EDITOR 编辑/测试，PUBLISHER 可审批/发布/启停/分流。审计 actor 由认证身份生成，不信任自报姓名。

审批：`POST /api/v1/admin/rules/{id}/approve`，body `{"expectedRevision":1,"reason":"reviewed"}`。审批者不能是最后编辑者，审批绑定草稿 revision，重新编辑自动失效。启用 `risk.rules.approval-required=true` 才强制发布审批；直接历史回滚被禁止，须复制历史内容为草稿再审批发布。个人 token 仍需密钥轮换、TLS、网络限流；不是完整 OIDC/SSO。

## 子 JVM 编译与执行

JVM 参数：

```
-Drisk.script.process.enabled=true
-Drisk.script.compile.timeout-ms=5000
-Drisk.script.process.max=4
```

主 JVM 不编译/加载用户脚本类，每次编译/执行创建一次性子 JVM，64MiB 堆、96MiB metaspace、单活动处理器，达到墙钟期限则销毁进程及后代。使用容量信号量拒绝过载，临时文件传输有大小约束。编译期限包含子 JVM 启动/编译等待，不是全链路硬实时保证。

**不是完整 OS 安全沙箱**：子进程已清除继承环境变量以减少凭证泄露，但仍继承应用 OS 用户权限，无法仅靠 Java API 禁止网络、磁盘和系统调用。不得接受不可信用户脚本。生产应部署独立 worker 容器/服务：无数据库/Redis/Adapter 凭证、无网络出口、只读根目录、独立 tmpfs、非 root、seccomp/AppArmor、cgroup CPU/内存/PID 限额。当前尚未提供该远程容器执行服务。

一次性 JVM 冷启动/重编译通常远超 2ms！启用前应显式调整单规则及整轮预算并压测；不能声称与原 <5ms 目标兼容。默认仍为可信脚本同 JVM 模式。Docker 采用展开 classes/lib classpath 以支持子进程；使用 fat jar 运行须提供 `-Drisk.script.worker.classpath=...` 指向展开的 classes/lib。

## 删除与结果模型

当前安全删除策略是先禁用，不物理清除历史/审计。编译缓存有容量/过期和关闭清理，GC 负责类卸载。自动归档/物理清除需要定义监管保留期，并禁止删除实验引用版本；尚未实现该工作流。

目前仅支持 ROUTE_A/ROUTE_B/null。拒单、限额、冻结、评分需要新增经过校验的动作协议，并调整订单/Adapter 幂等和响应语义，不能把任意字符串当交易指令。本轮没有贸然扩展这些动作。

## 必须完成的外部验收

1. MySQL 8：依次执行迁移；验证行锁、并发发布、审计写失败事务回滚、故障切换；H2 测试不能代替 MySQL。
2. Redis：真实 Lua 并发竞争、Cluster 同槽、连接超时、写成功但响应丢失、持久化和主从故障；确认不确定订单不会重投。
3. Adapter：真实 TLS、认证、协议、持久幂等、超时后查询核对；不得使用真实交易账户压测。
4. 压测：测试环境固定规则数/脚本大小/特征量；记录 p50/p95/p99/p999、吞吐、超时、线程池/旁路丢弃、堆/metaspace、GC。分别测试 OFF/SHADOW/CANARY/AB/子 JVM 模式。
5. 故障演练：停止 MySQL/Redis/Adapter、延迟/丢包、杀死网关/worker、日志队列满；断言无重复交易、降级可观测、未确认订单不释放。
6. 先旁路，确认错误率/差异，再小流量灰度；预设停止实验、禁用规则、回退版本步骤及责任人。生产门禁不得仅检查 HTTP 200。

真实系统联调、生产压测、故障演练、OS 沙箱、审计持久化可靠性验收均未在本地完成。
