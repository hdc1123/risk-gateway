package com.risk.gateway.adapter;

import com.risk.gateway.Models.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 不访问 MT5，不表示真实受理。只能用于 local/test 环境。 */
@Component
@ConditionalOnProperty(name = "risk.adapter-mode", havingValue = "mock")
public class MockMT5Adapter implements MT5Adapter {
    public MockMT5Adapter(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("local", "test")))
            throw new IllegalArgumentException("mock Adapter 仅允许 local/test，真实环境请配置 grpc");
    }
    @Override
    public RouteResult route(Decision decision, OrderRequest order, String traceId) {
        return new RouteResult("mock_" + UUID.nameUUIDFromBytes(
            AdapterProtocol.businessKey(order).getBytes(StandardCharsets.UTF_8)));
    }
}
