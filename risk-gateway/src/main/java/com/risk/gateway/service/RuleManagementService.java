package com.risk.gateway.service;

import com.risk.gateway.Models.Rule;
import com.risk.gateway.common.BusinessException;
import com.risk.gateway.config.RuleConfiguration;
import com.risk.gateway.config.SystemConfigService;
import com.risk.gateway.engine.GroovyRuleCompiler;
import com.risk.gateway.engine.ScriptGuard;
import jakarta.annotation.PreDestroy;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** 所有写操作先锁草稿行，串行化同一规则的编辑、发布、启停与回滚。 */
@Service
@ConditionalOnProperty(name="risk.rules.enabled", havingValue="true", matchIfMissing=true)
public class RuleManagementService {
    public record DraftRequest(@Min(0) int expectedRevision,
        @NotBlank @Size(max=128) String ruleName, int priority,
        @NotBlank @Size(max=65535) String scriptContent, @Min(0) @Max(1000) int timeoutMs,
        @NotBlank @Size(max=512) String reason) {}
    public record PublishRequest(@Min(1) int expectedRevision, @Min(0) int expectedVersion,
        @NotBlank @Size(max=512) String reason) {}
    public record StateRequest(@Min(1) int expectedVersion, boolean enabled,
        @NotBlank @Size(max=512) String reason) {}
    public record RollbackRequest(@Min(1) int expectedVersion, @Min(1) int targetVersion,
        @NotBlank @Size(max=512) String reason) {}
    public record Draft(String ruleId, String ruleName, int priority, String scriptContent, int timeoutMs, int revision) {
        Rule rule() { return new Rule(ruleId, ruleName, priority, scriptContent, null, "ROUTE", timeoutMs); }
    }
    public record Active(int version, boolean enabled) {}
    public record ApprovalRequest(@Min(1) int expectedRevision, @NotBlank @Size(max=512) String reason) {}
    private boolean approvalRequired;
    @org.springframework.beans.factory.annotation.Value("${risk.rules.approval-required:false}")
    public void setApprovalRequired(boolean required) { this.approvalRequired=required; }

    @Transactional
    public int approve(String id, ApprovalRequest request, String actor, String trace) {
        validateId(id);
        Draft draft=findDraft(id,true);
        if (draft.revision()!=request.expectedRevision()) throw conflict();
        String editor=jdbc.queryForObject("SELECT updated_by FROM risk_rule_draft WHERE rule_id=?",String.class,id);
        if (actor.equals(editor) || actor.equals("admin-token")) throw new BusinessException(1403,"审批者必须与编辑者不同且使用个人身份");
        validate(draft.rule());
        jdbc.update("INSERT INTO risk_rule_approval (rule_id,revision,approver,reason) VALUES (?,?,?,?)",
            id,draft.revision(),actor,request.reason());
        audit(id,"APPROVE",draft.revision(),actor,request.reason(),trace);
        return draft.revision();
    }
    private final JdbcTemplate jdbc;
    private final GroovyRuleCompiler compiler;
    private final int maxScriptBytes;

    public RuleManagementService(JdbcTemplate jdbc, RuleConfiguration.Properties properties, SystemConfigService configs) {
        this.jdbc = jdbc;
        this.maxScriptBytes = properties.maxScriptBytes();
        this.compiler = new GroovyRuleCompiler(new ScriptGuard(), properties, configs);
    }

    public Draft draft(String id) { validateId(id); return findDraft(id, false); }
    public Active status(String id) {
        validateId(id);
        return jdbc.query("SELECT script_version,enabled FROM risk_rule WHERE rule_id=?",
            (rs,n) -> new Active(rs.getInt(1), rs.getBoolean(2)), id).stream()
            .findFirst().orElse(new Active(0, false));
    }

    @Transactional
    public Draft save(String id, DraftRequest request, String actor, String trace) {
        validateId(id);
        // 草稿允许暂存语法错误，但大小与基础防误用检查始终生效。
        new ScriptGuard().validate(request.scriptContent(), maxScriptBytes);
        if (request.expectedRevision() == 0) {
            try {
                jdbc.update("INSERT INTO risk_rule_draft (rule_id,rule_name,priority,script_content,timeout_ms,revision,updated_by) VALUES (?,?,?,?,?,1,?)",
                    id, request.ruleName(), request.priority(), request.scriptContent(), request.timeoutMs(), actor);
            } catch (DuplicateKeyException e) { throw conflict(); }
        } else {
            Draft old = findDraft(id, true);
            if (old.revision() != request.expectedRevision()) throw conflict();
            jdbc.update("UPDATE risk_rule_draft SET rule_name=?,priority=?,script_content=?,timeout_ms=?,revision=revision+1,updated_by=? WHERE rule_id=?",
                request.ruleName(), request.priority(), request.scriptContent(), request.timeoutMs(), actor, id);
        }
        Draft saved = findDraft(id, false);
        audit(id, "SAVE_DRAFT", saved.revision(), actor, request.reason(), trace);
        return saved;
    }

    @Transactional
    public Active publish(String id, PublishRequest request, String actor, String trace) {
        validateId(id);
        Draft draft = findDraft(id, true);
        if (draft.revision() != request.expectedRevision()) throw conflict();
        if (approvalRequired) {
            Integer approvals=jdbc.queryForObject("SELECT COUNT(*) FROM risk_rule_approval WHERE rule_id=? AND revision=? AND approver<>?",
                Integer.class,id,draft.revision(),"admin-token");
            if (approvals==null || approvals!=1) throw new BusinessException(1403,"当前草稿未获独立审批");
        }
        Active old = active(id);
        if (old.version() != request.expectedVersion()) throw conflict();
        return release(draft.rule(), old, actor, request.reason(), trace, "PUBLISH");
    }

    @Transactional
    public Active setEnabled(String id, StateRequest request, String actor, String trace) {
        validateId(id);
        findDraft(id, true);
        Active old = active(id);
        if (old.version() == 0) throw new BusinessException(1404, "规则尚未发布");
        if (old.version() != request.expectedVersion()) throw conflict();
        if (request.enabled()) {
            Rule rule = jdbc.query("SELECT * FROM risk_rule WHERE rule_id=?", (rs, n) -> new Rule(id,
                rs.getString("rule_name"), rs.getInt("priority"), rs.getString("script_content"),
                rs.getString("script_md5"), rs.getString("return_type"), rs.getInt("timeout_ms")), id).get(0);
            validate(rule);
        }
        // 状态变化也推进版本，避免启停操作的 ABA 并发覆盖；保存对应版本内容。
        int version = old.version() + 1;
        jdbc.update("UPDATE risk_rule SET enabled=?,script_version=?,updated_by=? WHERE rule_id=?",
            request.enabled() ? 1 : 0, version, actor, id);
        Rule current = jdbc.query("SELECT rule_name,priority,script_content,script_md5,return_type,timeout_ms FROM risk_rule WHERE rule_id=?",
            (rs,n) -> new Rule(id, rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)), id)
            .stream().findFirst().orElseThrow(() -> new BusinessException(1404, "规则不存在"));
        jdbc.update("INSERT INTO risk_rule_release (rule_id,version,rule_name,priority,script_content,script_md5,timeout_ms,published_by,reason) VALUES (?,?,?,?,?,?,?,?,?)",
            id, version, current.ruleName(), current.priority(), current.scriptContent(),
            GroovyRuleCompiler.digest("MD5", current.scriptContent()), current.timeoutMs(), actor, request.reason());
        audit(id, request.enabled() ? "ENABLE" : "DISABLE", version, actor, request.reason(), trace);
        return new Active(version, request.enabled());
    }

    @Transactional
    public Active rollback(String id, RollbackRequest request, String actor, String trace) {
        validateId(id);
        findDraft(id, true);
        if (approvalRequired) throw new BusinessException(1403,"审批模式下请将历史内容保存为新草稿，审批后发布");
        Active old = active(id);
        if (old.version() != request.expectedVersion()) throw conflict();
        List<Rule> rules = jdbc.query("SELECT * FROM risk_rule_release WHERE rule_id=? AND version=?", (rs, n) ->
            new Rule(id, rs.getString("rule_name"), rs.getInt("priority"), rs.getString("script_content"),
                rs.getString("script_md5"), "ROUTE", rs.getInt("timeout_ms")), id, request.targetVersion());
        if (rules.isEmpty()) throw new BusinessException(1404, "历史版本不存在");
        return release(rules.get(0), old, actor, request.reason(), trace, "ROLLBACK");
    }

    public List<Map<String,Object>> history(String id, int limit) {
        validateId(id); checkLimit(limit);
        return jdbc.queryForList("SELECT version,rule_name,priority,script_md5,timeout_ms,published_by,reason,published_at FROM risk_rule_release WHERE rule_id=? ORDER BY version DESC LIMIT ?", id, limit);
    }
    public List<Map<String,Object>> audits(String id, int limit) {
        validateId(id); checkLimit(limit);
        return jdbc.queryForList("SELECT id,operation,revision,actor,reason,trace_id,created_at FROM risk_rule_audit WHERE rule_id=? ORDER BY id DESC LIMIT ?", id, limit);
    }

    private Active release(Rule rule, Active old, String actor, String reason, String trace, String operation) {
        String md5 = validate(rule);
        int version = old.version() + 1;
        // 首次发布默认禁用；更新和回滚保留已有启停状态。
        if (old.version() == 0) {
            jdbc.update("INSERT INTO risk_rule (rule_id,rule_name,priority,enabled,script_content,script_version,script_md5,return_type,timeout_ms,created_by,updated_by) VALUES (?,?,?,0,?,?,?,'ROUTE',?,?,?)",
                rule.ruleId(), rule.ruleName(), rule.priority(), rule.scriptContent(), version, md5, rule.timeoutMs(), actor, actor);
        } else {
            jdbc.update("UPDATE risk_rule SET rule_name=?,priority=?,script_content=?,script_version=?,script_md5=?,timeout_ms=?,updated_by=? WHERE rule_id=?",
                rule.ruleName(), rule.priority(), rule.scriptContent(), version, md5, rule.timeoutMs(), actor, rule.ruleId());
        }
        jdbc.update("INSERT INTO risk_rule_release (rule_id,version,rule_name,priority,script_content,script_md5,timeout_ms,published_by,reason) VALUES (?,?,?,?,?,?,?,?,?)",
            rule.ruleId(), version, rule.ruleName(), rule.priority(), rule.scriptContent(), md5, rule.timeoutMs(), actor, reason);
        audit(rule.ruleId(), operation, version, actor, reason, trace);
        return new Active(version, old.enabled());
    }

    private String validate(Rule rule) {
        try { return compiler.compile(rule).digest(); }
        catch (RuntimeException e) { throw new BusinessException(1400, "规则编译或安全校验失败，未发布"); }
    }
    private Draft findDraft(String id, boolean lock) {
        List<Draft> rows = jdbc.query("SELECT * FROM risk_rule_draft WHERE rule_id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> new Draft(id, rs.getString("rule_name"), rs.getInt("priority"), rs.getString("script_content"), rs.getInt("timeout_ms"), rs.getInt("revision")), id);
        if (rows.isEmpty()) throw new BusinessException(1404, "规则草稿不存在");
        return rows.get(0);
    }
    private Active active(String id) {
        return jdbc.query("SELECT script_version,enabled FROM risk_rule WHERE rule_id=? FOR UPDATE",
            (rs,n) -> new Active(rs.getInt(1), rs.getBoolean(2)), id).stream().findFirst().orElse(new Active(0, false));
    }
    private void audit(String id, String operation, int revision, String actor, String reason, String trace) {
        jdbc.update("INSERT INTO risk_rule_audit (rule_id,operation,revision,actor,reason,trace_id) VALUES (?,?,?,?,?,?)",
            id, operation, revision, actor, reason, trace);
    }
    private static BusinessException conflict() { return new BusinessException(1409, "规则版本已变更，请重新读取"); }
    private static void validateId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._:-]{1,64}")) throw new IllegalArgumentException("规则ID格式错误");
    }
    private static void checkLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit 必须为 1~100");
    }
    @PreDestroy public void close() { compiler.close(); }
}
