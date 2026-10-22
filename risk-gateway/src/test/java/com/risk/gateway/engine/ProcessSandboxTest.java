package com.risk.gateway.engine;

import com.risk.gateway.Models.Rule;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class ProcessSandboxTest {
    @Test void compilesAndExecutesOutsideParentJvm() throws Exception {
        Rule rule=new Rule("test","test",1,"return 'ROUTE_A'",null,"ROUTE",1000);
        var reply=ProcessSandbox.call(new ProcessSandbox.Request(rule,Map.of(),false),15000);
        assertNull(reply.error()); assertEquals("ROUTE_A",reply.value());
    }
    @Test void parentKillsWorkerWhenDeadlineExpires() {
        Rule rule=new Rule("test","test",1,"while (true) { }; return null",null,"ROUTE",1000);
        long start=System.nanoTime();
        assertThrows(TimeoutException.class,()->ProcessSandbox.call(new ProcessSandbox.Request(rule,Map.of(),false),2000));
        assertTrue(System.nanoTime()-start<10_000_000_000L);
    }
}
