package com.risk.gateway.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.risk.gateway.Models.Rule;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 一次性子 JVM。必须额外配合容器网络、文件系统及 cgroup 限制，不等同 OS 沙箱。 */
public final class ProcessSandbox {
    public record Request(Rule rule, Map<String,Object> features, boolean compileOnly) {}
    public record Reply(String value, String error) {}
    private static final Semaphore SLOTS=new Semaphore(Integer.getInteger("risk.script.process.max",4));
    private static final ObjectMapper JSON=new ObjectMapper();
    private ProcessSandbox() {}
    public static boolean enabled() { return Boolean.getBoolean("risk.script.process.enabled"); }
    public static Reply call(Request request,long timeoutMs) throws Exception {
        if (timeoutMs<1) throw new TimeoutException("沙箱预算耗尽");
        if (!SLOTS.tryAcquire()) throw new RejectedExecutionException("沙箱容量耗尽");
        Process process=null;
        Path input=null,output=null;
        try {
            byte[] bytes=JSON.writeValueAsBytes(request);
            if (bytes.length>262144) throw new IllegalArgumentException("沙箱输入超限");
            input=Files.createTempFile("risk-worker-in-",".json");
            output=Files.createTempFile("risk-worker-out-",".json");
            Files.write(input,bytes);
            String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
            ProcessBuilder builder=new ProcessBuilder(java,"-Xmx64m","-XX:MaxMetaspaceSize=96m","-XX:ActiveProcessorCount=1",
                "-cp",System.getProperty("risk.script.worker.classpath",System.getProperty("java.class.path")),
                SandboxWorker.class.getName())
                .redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            // 不向 worker 泄露数据库、Redis、Adapter 或云平台环境凭证。
            builder.environment().clear();
            process=builder.start();
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (!process.waitFor(Math.min(timeoutMs,10),TimeUnit.MILLISECONDS)) {
                if (Files.size(output)>4096) throw new IllegalStateException("沙箱输出超限");
                if (System.nanoTime()>=deadline) throw new TimeoutException("沙箱超时");
            }
            if (process.exitValue()!=0 || Files.size(output)>4096) throw new IllegalStateException("沙箱进程失败");
            return JSON.readValue(output.toFile(),Reply.class);
        } finally {
            if (process!=null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                // waitFor 的中断状态由调用者恢复；清理不无限等待。
            }
            try {
                if (input!=null) Files.deleteIfExists(input);
                if (output!=null) Files.deleteIfExists(output);
            } finally { SLOTS.release(); }
        }
    }
}
