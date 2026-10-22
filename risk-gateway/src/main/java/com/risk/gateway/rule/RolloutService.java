package com.risk.gateway.rule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.risk.gateway.Models.*;
import com.risk.gateway.common.BusinessException;
import com.risk.gateway.config.RuleConfiguration;
import com.risk.gateway.config.SystemConfigService;
import com.risk.gateway.engine.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** 候选集为完整规则集而非补丁；独立线程池旁路运行永不调用 Adapter。 */
@Service
@ConditionalOnProperty(name="risk.rules.enabled", havingValue="true", matchIfMissing=true)
public class RolloutService {
    public enum Mode { OFF, SHADOW, CANARY, AB }
    public record Change(@Min(0) int expectedRevision, @NotNull Mode mode,
        @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,64}") String experimentId,
        @NotBlank @Size(max=64) String salt, @Min(0) @Max(10000) int trafficBps,
        @NotNull @Size(max=1000) Map<String,Integer> candidateVersions,
        @NotBlank @Size(max=512) String reason) {}
    public record Plan(int revision, Mode mode, String experimentId, String salt, int trafficBps,
                       Map<String,Integer> versions, List<CompiledRule> rules, Instant loadedAt) {
        public Plan { versions=Map.copyOf(versions); rules=List.copyOf(rules); }
    }
    public record Status(int revision, Mode mode, String experimentId, int trafficBps,
                         Map<String,Integer> candidateVersions, Instant loadedAt, String error) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final GroovyRuleCompiler compiler;
    private final ScriptRunner runner;
    private final Executor shadowExecutor;
    private final MeterRegistry metrics;
    private final int maxRules;
    @org.springframework.beans.factory.annotation.Value("${risk.rules.evaluation-budget-ms:2}")
    private int evaluationBudgetMs=2;
    @org.springframework.beans.factory.annotation.Value("${risk.rules.approval-required:false}")
    private boolean approvalRequired;
    private volatile Plan plan = new Plan(0,Mode.OFF,"none","initial",0,Map.of(),List.of(),Instant.EPOCH);
    private volatile String error="NOT_LOADED";
    public RolloutService(JdbcTemplate jdbc, ObjectMapper mapper, RuleConfiguration.Properties properties,
                         SystemConfigService configs, @Qualifier("shadowScriptExecutor") Executor scriptExecutor,
                         @Qualifier("shadowExecutor") Executor shadowExecutor, MeterRegistry metrics) {
        this.jdbc=jdbc; this.mapper=mapper; this.compiler=new GroovyRuleCompiler(new ScriptGuard(),properties,configs);
        this.runner=new ScriptRunner(scriptExecutor); this.shadowExecutor=shadowExecutor; this.metrics=metrics;
        this.maxRules=properties.maxActiveRules();
    }
    @PostConstruct @Scheduled(fixedDelayString="${risk.rollout.refresh-ms:5000}")
    public synchronized void refresh() {
        try {
            Plan next=jdbc.queryForObject("SELECT * FROM risk_rollout WHERE id=1", (rs,n) -> {
                try {
                    Map<String,Integer> versions=mapper.readValue(rs.getString("candidate_versions"),new TypeReference<Map<String,Integer>>() {});
                    Mode mode=Mode.valueOf(rs.getString("mode"));
                    return new Plan(rs.getInt("revision"), mode, rs.getString("experiment_id"), rs.getString("salt"),
                        rs.getInt("traffic_bps"),versions, mode==Mode.OFF ? List.of() : compile(versions),Instant.now());
                } catch (Exception e) { throw new IllegalStateException("实验配置或候选编译失败",e); }
            });
            if (next==null) throw new IllegalStateException("实验配置缺失");
            plan=next; error=null;
        } catch (RuntimeException e) {
            // 配置更新失败时停止实验，不沿用可能已被撤回的实验分流。
            error=e.getClass().getSimpleName();
        }
    }
    public Status status() {
        Plan current=plan;
        return new Status(current.revision(),current.mode(),current.experimentId(),current.trafficBps(),current.versions(),current.loadedAt(),error);
    }
    public Plan current() {
        Plan current=plan;
        return error==null && current.loadedAt().isAfter(Instant.now().minusSeconds(30)) ? current :
            new Plan(current.revision(),Mode.OFF,current.experimentId(),current.salt(),0,Map.of(),List.of(),current.loadedAt());
    }
    @Transactional
    public int configure(Change change, String actor, String trace) {
        if (change.mode()!=Mode.OFF && approvalRequired) {
            // 不允许通过实验接口绕过正式规则发布审批。OFF 紧急停止始终可用。
            throw new BusinessException(1403,"审批模式下实验配置尚未支持独立审批，仅允许停止实验");
        }
        if (change.mode()!=Mode.OFF) {
            if (change.candidateVersions().isEmpty()) throw new IllegalArgumentException("候选规则集不能为空");
            compile(change.candidateVersions());
        }
        try {
            int updated=jdbc.update("UPDATE risk_rollout SET revision=revision+1,mode=?,experiment_id=?,salt=?,traffic_bps=?,candidate_versions=? WHERE id=1 AND revision=?",
                change.mode().name(),change.experimentId(),change.salt(),change.trafficBps(),mapper.writeValueAsString(change.candidateVersions()),change.expectedRevision());
            if (updated!=1) throw new BusinessException(1409,"实验配置版本冲突");
            jdbc.update("INSERT INTO risk_rule_audit (rule_id,operation,revision,actor,reason,trace_id) VALUES (?,?,?,?,?,?)",
                "__rollout__","ROLLOUT_"+change.mode(),change.expectedRevision()+1,actor,change.reason(),trace);
            return change.expectedRevision()+1;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("实验配置序列化失败",e); }
    }
    private List<CompiledRule> compile(Map<String,Integer> versions) {
        if (versions.size()>maxRules) throw new IllegalArgumentException("候选规则数超限");
        List<CompiledRule> result=new ArrayList<>();
        versions.forEach((id,version) -> {
            if (id==null || !id.matches("[A-Za-z0-9._:-]{1,64}") || version==null || version<1)
                throw new IllegalArgumentException("候选版本不合法");
            List<Rule> rows=jdbc.query("SELECT * FROM risk_rule_release WHERE rule_id=? AND version=?", (rs,n) ->
                new Rule(id,rs.getString("rule_name"),rs.getInt("priority"),rs.getString("script_content"),rs.getString("script_md5"),"ROUTE",rs.getInt("timeout_ms")),id,version);
            if (rows.size()!=1) throw new BusinessException(1404,"候选规则版本不存在");
            result.add(compiler.compile(rows.get(0)));
        });
        result.sort(Comparator.comparingInt((CompiledRule r)->r.rule().priority()).reversed().thenComparing(r->r.rule().ruleId()));
        return List.copyOf(result);
    }
    public static int bucket(String experiment, String salt, String subject) {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            // 长度前缀避免字符串拼接歧义，不依赖 JVM hashCode。
            for (String value:List.of(experiment,salt,subject)) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            return (int) Long.remainderUnsigned(java.nio.ByteBuffer.wrap(hash.digest()).getLong(),10000);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public boolean selected(Plan current, String subject) {
        boolean selected=current.mode()!=Mode.OFF && bucket(current.experimentId(),current.salt(),subject)<current.trafficBps();
        if (current.mode()!=Mode.OFF) metrics.counter("risk.rollout.assignment","mode",current.mode().name(),
            "arm",selected ? "candidate" : "control").increment();
        return selected;
    }
    public Evaluation evaluateCandidate(Plan current, Map<String,Object> features) {
        return GroovyRuleEngine.evaluateRules(current.rules(),runner,features,TimeUnit.MILLISECONDS.toNanos(evaluationBudgetMs),false);
    }
    public void shadow(Plan current, Map<String,Object> features, Evaluation baseline) {
        Map<String,Object> safe=GroovyRuleEngine.immutableFeatures(features);
        try {
            shadowExecutor.execute(() -> {
                Evaluation candidate=evaluateCandidate(current,safe);
                String outcome=candidate.degraded() ? "error" : Objects.equals(winner(baseline),winner(candidate)) ? "same" : "different";
                metrics.counter("risk.rollout.shadow", "outcome",outcome).increment();
            });
        } catch (RejectedExecutionException e) { metrics.counter("risk.rollout.shadow","outcome","dropped").increment(); }
    }
    private static Book winner(Evaluation evaluation) {
        return evaluation.hits().stream().sorted(Comparator.comparingInt(RuleResult::priority).reversed().thenComparing(RuleResult::ruleId))
            .map(RuleResult::value).findFirst().orElse(Book.B_BOOK);
    }
    @PreDestroy public void close() { compiler.close(); }
}
