package com.risk.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuleConfiguration.Properties.class)
public class RuleConfiguration {
    /** 启动期参数；system_config 的动态应用将在配置模块实现。 */
    @Validated
    @ConfigurationProperties(prefix = "risk.rules")
    public record Properties(
        @Min(1) @Max(3600) int refreshIntervalSeconds,
        @Min(1) @Max(10000) int cacheSize,
        @Min(1) @Max(10000) int maxActiveRules,
        @Min(1) @Max(65535) int maxScriptBytes,
        @Min(1) @Max(1000) int defaultTimeoutMs,
        @Min(1) @Max(3600) int maxStalenessSeconds) {}
}
