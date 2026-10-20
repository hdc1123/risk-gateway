package com.risk.gateway.service;

import com.risk.gateway.Models.*;
import com.risk.gateway.common.BusinessException;
import com.risk.gateway.engine.*;
import com.risk.gateway.repository.RiskRuleRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(name="risk.rules.enabled", havingValue="true", matchIfMissing=true)
public class RuleTestService {
    private final RiskRuleRepository repository;
    private final GroovyRuleCompiler compiler;
    private final ScriptRunner runner;
    public RuleTestService(RiskRuleRepository repository,
                           com.risk.gateway.config.RuleConfiguration.Properties properties,
                           @Qualifier("adminScriptExecutor") Executor executor,
                           com.risk.gateway.config.SystemConfigService configs) {
        this.repository = repository;
        // 独立缓存和执行线程池，测试不修改生产快照、不占用生产脚本线程。
        this.compiler = new GroovyRuleCompiler(new ScriptGuard(), properties, configs);
        this.runner = new ScriptRunner(executor);
    }
    public ScriptResult test(String ruleId, TestRequest request) {
        if (!ruleId.matches("[A-Za-z0-9._:-]{1,64}")) throw new IllegalArgumentException("规则ID格式错误");
        if (request.profile() != null) throw new IllegalArgumentException("MVP1 profile 必须为空");
        if (request.features().size() > 32) throw new IllegalArgumentException("模拟特征字段数量超限");
        var features = GroovyRuleEngine.immutableFeatures(request.features());
        Rule rule = repository.findById(ruleId).orElseThrow(() -> new BusinessException(1404, "规则不存在"));
        long start = System.nanoTime();
        CompiledRule compiled;
        try { compiled = compiler.compile(rule); }
        catch (RuntimeException e) {
            return new ScriptResult(ruleId, rule.scriptMd5(), null, null,
                (System.nanoTime()-start)/1_000_000.0, false, "COMPILE_FAILED");
        }
        return runner.execute(compiled, features, TimeUnit.MILLISECONDS.toNanos(compiled.rule().timeoutMs()));
    }
    @jakarta.annotation.PreDestroy
    public void close() { compiler.close(); }
}
