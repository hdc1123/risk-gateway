package com.risk.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 有界线程池：过载时拒绝，禁止 CallerRuns 绕过超时保护。 */
@Configuration
public class ExecutorConfiguration {
    @Bean(name = "riskExecutor")
    public ThreadPoolTaskExecutor riskExecutor(SystemConfigService configs) {
        var startup = configs.startup();
        return executor("risk-gateway-", startup.poolCore(), startup.poolMax(), 1000);
    }

    @Bean(name = "scriptExecutor")
    public ThreadPoolTaskExecutor scriptExecutor() {
        return executor("groovy-script-", 4, 8, 500);
    }

    @Bean(name = "adminExecutor")
    public ThreadPoolTaskExecutor adminExecutor() {
        return executor("risk-admin-", 1, 2, 16);
    }

    @Bean(name = "adminScriptExecutor")
    public ThreadPoolTaskExecutor adminScriptExecutor() {
        return executor("groovy-admin-", 1, 2, 16);
    }

    @Bean(name = "shadowExecutor")
    public ThreadPoolTaskExecutor shadowExecutor() { return executor("risk-shadow-", 1, 2, 32); }

    @Bean(name = "shadowScriptExecutor")
    public ThreadPoolTaskExecutor shadowScriptExecutor() { return executor("groovy-shadow-", 1, 2, 32); }

    private ThreadPoolTaskExecutor executor(String prefix, int core, int max, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(capacity);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
