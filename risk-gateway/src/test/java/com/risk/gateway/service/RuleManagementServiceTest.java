package com.risk.gateway.service;

import com.risk.gateway.common.BusinessException;
import com.risk.gateway.config.RuleConfiguration;
import com.risk.gateway.config.SystemConfigService;
import com.risk.gateway.service.RuleManagementService.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleManagementServiceTest {
    private JdbcTemplate jdbc;
    private RuleManagementService service;
    private TransactionTemplate tx;
    @BeforeEach void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE risk_rule_draft (rule_id VARCHAR(64) PRIMARY KEY,rule_name VARCHAR(128),priority INT,script_content TEXT,timeout_ms INT,revision INT,updated_by VARCHAR(128))");
        jdbc.execute("CREATE TABLE risk_rule (rule_id VARCHAR(64) PRIMARY KEY,rule_name VARCHAR(128),priority INT,enabled INT,script_content TEXT,script_version INT,script_md5 VARCHAR(32),return_type VARCHAR(32),timeout_ms INT,created_by VARCHAR(128),updated_by VARCHAR(128))");
        jdbc.execute("CREATE TABLE risk_rule_release (rule_id VARCHAR(64),version INT,rule_name VARCHAR(128),priority INT,script_content TEXT,script_md5 VARCHAR(32),timeout_ms INT,published_by VARCHAR(128),reason VARCHAR(512),published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(rule_id,version))");
        jdbc.execute("CREATE TABLE risk_rule_audit (id BIGINT AUTO_INCREMENT PRIMARY KEY,rule_id VARCHAR(64),operation VARCHAR(32),revision INT,actor VARCHAR(128),reason VARCHAR(512),trace_id VARCHAR(64),created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE risk_rule_approval (rule_id VARCHAR(64),revision INT,approver VARCHAR(128),reason VARCHAR(512),PRIMARY KEY(rule_id,revision))");
        service = new RuleManagementService(jdbc, new RuleConfiguration.Properties(30,100,100,32768,2,120), mock(SystemConfigService.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @AfterEach void close() { service.close(); }
    private Draft save(int revision, String script) {
        return tx.execute(status -> service.save("TEST", new DraftRequest(revision,"测试",100,script,0,"edit"),"admin","trace"));
    }
    private Active publish(int revision, int version) {
        return tx.execute(status -> service.publish("TEST",new PublishRequest(revision,version,"publish"),"admin","trace"));
    }
    @Test void draftDoesNotChangeLiveRuleAndReleaseIsVersioned() {
        assertEquals(1, save(0,"return 'ROUTE_A'").revision());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_rule", Integer.class));
        assertEquals(new Active(1,false), publish(1,0));
        assertEquals(new Active(2,true), tx.execute(s -> service.setEnabled("TEST",new StateRequest(1,true,"enable"),"admin","trace")));
        save(1,"return 'ROUTE_B'");
        assertEquals("return 'ROUTE_A'", jdbc.queryForObject("SELECT script_content FROM risk_rule",String.class));
        assertEquals(new Active(3,true), publish(2,2));
        assertEquals(new Active(4,true), tx.execute(s -> service.rollback("TEST",new RollbackRequest(3,1,"rollback"),"admin","trace")));
        assertEquals("return 'ROUTE_A'", jdbc.queryForObject("SELECT script_content FROM risk_rule",String.class));
        assertEquals(4, service.history("TEST",20).size());
        assertEquals(6, service.audits("TEST",20).size());
    }
    @Test void invalidPublishAndStaleWritesDoNotChangeProduction() {
        save(0,"return null"); publish(1,0);
        save(1,"if (");
        BusinessException failure = assertThrows(BusinessException.class, () -> publish(2,1));
        assertEquals(1400, failure.code());
        assertEquals(1, service.history("TEST",20).size());
        assertThrows(BusinessException.class, () -> save(1,"return null"));
        assertThrows(BusinessException.class, () -> publish(1,1));
        assertEquals(2, service.draft("TEST").revision());
    }
    @Test void approvalIsBoundToRevisionAndCannotBeSelfApproved() {
        service.setApprovalRequired(true);
        save(0,"return null");
        assertEquals(1403,assertThrows(BusinessException.class,()->publish(1,0)).code());
        assertThrows(BusinessException.class,()->tx.execute(s->service.approve("TEST",new ApprovalRequest(1,"approve"),"admin","trace")));
        tx.execute(s->service.approve("TEST",new ApprovalRequest(1,"approve"),"reviewer","trace"));
        publish(1,0);
        save(1,"return 'ROUTE_A'");
        assertEquals(1403,assertThrows(BusinessException.class,()->publish(2,1)).code());
        assertEquals(1403,assertThrows(BusinessException.class,()->tx.execute(s->service.rollback("TEST",new RollbackRequest(1,1,"rollback"),"admin","trace"))).code());
    }
    @Test void auditFailureRollsBackDraft() {
        jdbc.execute("DROP TABLE risk_rule_audit");
        assertThrows(RuntimeException.class, () -> save(0,"return null"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM risk_rule_draft",Integer.class));
    }
    @Test void concurrentUpdatesCannotOverwriteEachOther() throws Exception {
        save(0,"return null");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> update = () -> {
            start.await();
            try { save(1,"return 'ROUTE_A'"); return true; }
            catch (BusinessException e) { assertEquals(1409,e.code()); return false; }
        };
        try {
            Future<Boolean> first = pool.submit(update), second = pool.submit(update);
            start.countDown();
            assertNotEquals(first.get(10,TimeUnit.SECONDS), second.get(10,TimeUnit.SECONDS));
            assertEquals(2,service.draft("TEST").revision());
        } finally { pool.shutdownNow(); }
    }
}
