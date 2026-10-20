# MT5 Adapter 同步路由契约 v1

本契约为 Risk Gateway 新定义的接口，**不是同级 trader-adapter 已提供的接口**。当前未发现可复用的 gRPC 定义，需要 Adapter 服务端实现或编写桥接层；本轮只提供客户端，不自动执行真实 MT5 交易。

## 传输

- Unary gRPC 方法：`risk.adapter.v1.OrderRouter/Route`
- 消息体：UTF-8 JSON，自定义 marshaller，**不是 protobuf**；普通 protobuf stub 不能直接互通。
- TLS 使用 JVM 信任库验证服务端；私有 CA 需通过 JVM trustStore 部署。
- Metadata：`authorization: Bearer <ADAPTER_TOKEN>`，服务端必须验证权限。
- A_BOOK 和 B_BOOK 使用独立的可配置地址。客户端禁用自动重试，不自动故障切换通道。
- 默认 deadline 与客户端等待预算 3ms；冷连接、TLS、网络、JVM 调度可能超时，上线前必须预热并压测。

## 请求

```json
{
  "version": 1,
  "idempotencyKey": "10001:880001:ord_20261008_001",
  "traceId": "trace_abc123",
  "bookType": "A_BOOK",
  "order": {
    "eventId": "evt_20261008_001",
    "eventType": "ORDER_SUBMIT",
    "userId": 10001,
    "mt5Account": 880001,
    "orderId": "ord_20261008_001",
    "symbol": "EURUSD",
    "volume": 1.00,
    "side": "BUY",
    "price": 1.0850,
    "orderType": "MARKET",
    "timestamp": 1791504000000
  }
}
```

## 响应

```json
{
  "version": 1,
  "idempotencyKey": "10001:880001:ord_20261008_001",
  "bookType": "A_BOOK",
  "status": "ACCEPTED",
  "adapterOrderId": "adp_20261008_001"
}
```

- ACCEPTED：已持久化受理，可返回成功，但不代表已成交。
- DUPLICATE：同一业务订单已受理，返回原 adapterOrderId，不能再次转发。
- REJECTED：确定未产生外部交易副作用。仅用于适配器受理失败，不是 MVP1 风控 REJECT 处置动作。
- 其他状态、响应业务键/通道不匹配、无订单号、RPC 失败或超时：结果不确定，禁止自动重投。

## 服务端必须保证

1. 在真实 MT5 副作用之前持久化业务幂等键，校验请求内容与原路由；相同键不同内容不能执行第二次。
2. 幂等唯一性跨 A/B 通道、跨实例及重启，不能各通道独立去重后分别执行同一订单。
3. 对订单结果未知的情形不得返回 REJECTED；应提供业务订单查询与人工核对能力。
4. 取消 RPC 或 deadline 到期不意味着交易被取消。服务端已受理后即使客户端超时，也不能删除受理记录。
5. 后续执行与回报依赖现有交易执行体系；网关响应仅表达受理结果。

## 配置

非 local/test 环境必须使用 grpc 且提供 ADAPTER_TOKEN。默认开启 TLS；明文仅允许 local/test。mock 不访问 MT5，订单号以 `mock_` 开头。

本轮未编译、运行或执行 gRPC 集成测试，需完成协议兼容、鉴权、幂等、超时和通道联调后才能上线。
