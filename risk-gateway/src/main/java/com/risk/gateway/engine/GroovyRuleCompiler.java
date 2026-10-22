package com.risk.gateway.engine;

import com.risk.gateway.Models.CompiledRule;
import com.risk.gateway.Models.Rule;
import com.risk.gateway.config.RuleConfiguration;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import groovy.transform.ThreadInterrupt;
import jakarta.annotation.PreDestroy;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

/** 只由后台加载及管理测试调用，禁止在订单热路径编译。 */
@Component
public class GroovyRuleCompiler {
    private final ScriptGuard guard;
    private final RuleConfiguration.Properties properties;
    private final Cache<String, Class<? extends Script>> cache;
    private final com.risk.gateway.config.SystemConfigService configs;

    public GroovyRuleCompiler(ScriptGuard guard, RuleConfiguration.Properties properties) {
        this(guard, properties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GroovyRuleCompiler(ScriptGuard guard, RuleConfiguration.Properties properties,
                              com.risk.gateway.config.SystemConfigService configs) {
        this.guard = guard;
        this.properties = properties;
        this.configs = configs;
        int capacity = configs != null && configs.enabled() ? configs.startup().scriptCacheSize() : properties.cacheSize();
        this.cache = Caffeine.newBuilder().maximumSize(capacity)
            .expireAfterAccess(Duration.ofHours(1)).build();
    }

    public synchronized CompiledRule compile(Rule rule) {
        if (rule.ruleId() == null || rule.ruleId().isBlank()) throw new IllegalArgumentException("规则ID为空");
        if (!"ROUTE".equals(rule.returnType())) throw new IllegalArgumentException("MVP1 仅支持 ROUTE");
        if (rule.timeoutMs() == 0) {
            int timeout = configs != null && configs.enabled() ? configs.current().scriptTimeoutMs() : properties.defaultTimeoutMs();
            rule = new Rule(rule.ruleId(), rule.ruleName(), rule.priority(), rule.scriptContent(), rule.scriptMd5(), rule.returnType(), timeout);
        }
        if (rule.timeoutMs() < 1 || rule.timeoutMs() > 1000) throw new IllegalArgumentException("规则超时必须为 0（继承）或 1~1000ms");
        guard.validate(rule.scriptContent(), properties.maxScriptBytes());
        String md5 = digest("MD5", rule.scriptContent());
        if (rule.scriptMd5() != null && !rule.scriptMd5().isBlank() && !md5.equalsIgnoreCase(rule.scriptMd5()))
            throw new IllegalArgumentException("script_md5 与脚本内容不一致，请同步更新 MD5");
        if (ProcessSandbox.enabled()) {
            try {
                var reply=ProcessSandbox.call(new ProcessSandbox.Request(rule, java.util.Map.of(), true),
                    Long.getLong("risk.script.compile.timeout-ms",5000));
                if (reply.error()!=null) throw new IllegalArgumentException("沙箱编译失败");
                // 子进程模式不在主 JVM 加载脚本类。
                return new CompiledRule(rule,null,md5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("编译中断",e);
            } catch (Exception e) { throw new IllegalStateException("沙箱编译失败或超时",e); }
        }
        // MD5 仅校验数据库变更；缓存采用真实内容 SHA-256，避免错误 MD5 和摘要碰撞污染。
        String key = digest("SHA-256", rule.scriptContent());
        Class<? extends Script> clazz = cache.getIfPresent(key);
        if (clazz == null) {
            CompilerConfiguration config = new CompilerConfiguration();
            config.setDisabledGlobalASTTransformations(Set.of("groovy.grape.GrabAnnotationTransformation"));
            // 循环与方法入口注入中断检查，配合后续执行器 cancel(true)。不等于硬隔离。
            config.addCompilationCustomizers(new RuleAstGuard(), new ASTTransformationCustomizer(ThreadInterrupt.class));
            try (GroovyClassLoader loader = new GroovyClassLoader(getClass().getClassLoader(), config)) {
                clazz = loader.parseClass(rule.scriptContent(), "Rule_" + key + ".groovy").asSubclass(Script.class);
                cache.put(key, clazz);
                loader.clearCache();
            } catch (IOException e) {
                throw new IllegalStateException("释放 Groovy 类加载器失败", e);
            }
        }
        return new CompiledRule(rule, clazz, md5);
    }

    public static String digest(String algorithm, String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @PreDestroy
    public void close() { cache.invalidateAll(); cache.cleanUp(); }
}
