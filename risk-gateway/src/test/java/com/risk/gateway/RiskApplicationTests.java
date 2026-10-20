package com.risk.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("local")
@SpringBootTest
class RiskApplicationTests {
    @Test
    void contextLoads() {
        // 骨架阶段验证 Spring 上下文；不连接外部 MySQL、Redis 或 MT5。
    }
}
