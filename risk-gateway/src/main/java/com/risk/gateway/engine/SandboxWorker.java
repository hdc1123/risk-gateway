package com.risk.gateway.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.risk.gateway.config.RuleConfiguration;
import groovy.lang.Binding;
import java.util.Map;

/** 无 Spring、无数据库、无 Redis 的一次性执行入口。 */
public final class SandboxWorker {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        // 防止子进程再次创建子进程。
        System.setProperty("risk.script.process.enabled","false");
        ProcessSandbox.Request request=mapper.readValue(System.in,ProcessSandbox.Request.class);
        ProcessSandbox.Reply reply;
        try (var closeable=new WorkerCompiler()) {
            var compiled=closeable.compiler.compile(request.rule());
            if (request.compileOnly()) reply=new ProcessSandbox.Reply(null,null);
            else {
                var script=compiled.scriptClass().getDeclaredConstructor().newInstance();
                Binding binding=new Binding();
                binding.setVariable("features",GroovyRuleEngine.immutableFeatures(request.features()));
                binding.setVariable("profile",null); binding.setVariable("ext",Map.of());
                script.setBinding(binding);
                Object value=script.run();
                if (value!=null && (!(value instanceof String text) || !(text.equals("ROUTE_A") || text.equals("ROUTE_B"))))
                    reply=new ProcessSandbox.Reply(null,"INVALID_RETURN_VALUE");
                else reply=new ProcessSandbox.Reply((String)value,null);
            }
        } catch (Exception e) { reply=new ProcessSandbox.Reply(null,"WORKER_ERROR"); }
        mapper.writeValue(System.out,reply);
    }
    private static final class WorkerCompiler implements AutoCloseable {
        final GroovyRuleCompiler compiler=new GroovyRuleCompiler(new ScriptGuard(),
            new RuleConfiguration.Properties(30,1,1000,65535,1000,120));
        @Override public void close() { compiler.close(); }
    }
}
