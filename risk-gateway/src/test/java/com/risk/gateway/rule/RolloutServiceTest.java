package com.risk.gateway.rule;

import com.risk.gateway.Models.*;
import com.risk.gateway.engine.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RolloutServiceTest {
    @Test void stableBucketsAndDistribution() {
        int selected=0;
        for (int i=0;i<10000;i++) {
            int bucket=RolloutService.bucket("experiment","salt","account:"+i);
            assertEquals(bucket,RolloutService.bucket("experiment","salt","account:"+i));
            assertTrue(bucket>=0 && bucket<10000);
            if (bucket<1000) selected++;
        }
        assertTrue(selected>850 && selected<1150,"10% 分桶分布异常: "+selected);
        assertNotEquals(RolloutService.bucket("ab","v1","account"),RolloutService.bucket("ab","v2","account"));
    }
    @Test void shadowNeverReplacesBaseline() {
        var loader=mock(RuleLoader.class);
        var runner=mock(ScriptRunner.class);
        var rollout=mock(RolloutService.class);
        var plan=new RolloutService.Plan(1,RolloutService.Mode.SHADOW,"e","s",10000,Map.of(),List.of(),Instant.now());
        when(rollout.current()).thenReturn(plan);
        when(rollout.selected(plan,"subject")).thenReturn(true);
        when(loader.snapshot()).thenReturn(RuleSnapshot.empty());
        GroovyRuleEngine engine=new GroovyRuleEngine(loader,runner,100);
        engine.setRollout(rollout);
        var result=engine.evaluateOrder(Map.of(),"subject");
        assertTrue(result.degraded());
        verify(rollout).shadow(plan,Map.of(),result);
        verify(rollout,never()).evaluateCandidate(any(),anyMap());
    }
    @Test void candidateFailureFallsBackAndPreservesDegradation() {
        var loader=mock(RuleLoader.class);
        var rollout=mock(RolloutService.class);
        var plan=new RolloutService.Plan(1,RolloutService.Mode.AB,"e","s",5000,Map.of(),List.of(),Instant.now());
        var snapshot=new RuleSnapshot(List.of(),Map.of(),Instant.now(),Instant.now(),null);
        when(loader.snapshot()).thenReturn(snapshot); when(loader.isUsable(snapshot)).thenReturn(true);
        when(rollout.current()).thenReturn(plan); when(rollout.selected(plan,"subject")).thenReturn(true);
        when(rollout.evaluateCandidate(plan,Map.of())).thenReturn(new Evaluation(List.of(),1,true));
        GroovyRuleEngine engine=new GroovyRuleEngine(loader,mock(ScriptRunner.class),100);
        engine.setRollout(rollout);
        assertTrue(engine.evaluateOrder(Map.of(),"subject").degraded());
    }
}
