package com.risk.gateway.idempotency;

import com.risk.gateway.Models.OrderRequest;
import com.risk.gateway.Models.ResponseData;

public interface IdempotencyStore {
    enum Status { ACQUIRED, PROCESSING, COMPLETED, UNCERTAIN, CONFLICT }
    record Claim(Status status, String key, String ownerToken, ResponseData cached) {}

    Claim claim(String requestId, OrderRequest order);
    void complete(Claim claim, ResponseData response);
    /** 仅在确定尚未调用 Adapter 时允许释放。 */
    void releaseBeforeDispatch(Claim claim);
    /** 已尝试发送但结果未知，禁止自动重投；需 Adapter 查询核对后处理。 */
    void markUncertain(Claim claim);
}
