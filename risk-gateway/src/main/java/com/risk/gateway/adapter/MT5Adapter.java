package com.risk.gateway.adapter;

import com.risk.gateway.Models.*;

public interface MT5Adapter {
    /** 成功表示 Adapter 已持久化受理，不表示 MT5 已成交。 */
    RouteResult route(Decision decision, OrderRequest order, String traceId);
}
