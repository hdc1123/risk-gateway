package com.risk.gateway.engine;

import com.risk.gateway.Models.*;
import groovy.lang.Binding;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/** 每次调用独立 Script/Binding；仅接受经过编译器检查的受信任规则。 */
@Component
public class ScriptRunner {
    private final Executor executor;

    public ScriptRunner(@Qualifier("scriptExecutor") Executor executor) { this.executor = executor; }

    public ScriptResult execute(CompiledRule compiled, Map<String, Object> features, long budgetNanos) {
        long start = System.nanoTime();
        if (Thread.currentThread().isInterrupted()) return failure(compiled, start, "INTERRUPTED");
        long timeout = Math.min(TimeUnit.MILLISECONDS.toNanos(compiled.rule().timeoutMs()), budgetNanos);
        if (timeout <= 0) return failure(compiled, start, "BUDGET_EXCEEDED");
        FutureTask<ScriptResult> task = new FutureTask<>(() -> {
            if (compiled.scriptClass()==null) {
                var reply=ProcessSandbox.call(new ProcessSandbox.Request(compiled.rule(),features,false),
                    Math.max(1,TimeUnit.NANOSECONDS.toMillis(timeout)));
                if (reply.error()!=null) return failure(compiled,start,reply.error());
                String value=reply.value();
                RuleResult result=value==null ? null : new RuleResult(compiled.rule().ruleId(),compiled.rule().ruleName(),
                    compiled.rule().priority(),"ROUTE","book_type",value.equals("ROUTE_A") ? Book.A_BOOK : Book.B_BOOK);
                return new ScriptResult(compiled.rule().ruleId(),compiled.digest(),value,result,elapsed(start),true,null);
            }
            var script = compiled.scriptClass().getDeclaredConstructor().newInstance();
            Binding binding = new Binding();
            binding.setVariable("features", features);
            binding.setVariable("profile", null);
            binding.setVariable("ext", Map.of());
            script.setBinding(binding);
            Object value = script.run();
            RuleResult result = null;
            if (value != null) {
                // 不对任意脚本返回对象调用 toString，避免额外执行脚本代码。
                if (!(value instanceof String text) || !(text.equals("ROUTE_A") || text.equals("ROUTE_B")))
                    return failure(compiled, start, "INVALID_RETURN_VALUE");
                result = new RuleResult(compiled.rule().ruleId(), compiled.rule().ruleName(),
                    compiled.rule().priority(), "ROUTE", "book_type", text.equals("ROUTE_A") ? Book.A_BOOK : Book.B_BOOK);
            }
            return new ScriptResult(compiled.rule().ruleId(), compiled.digest(), value, result,
                elapsed(start), true, null);
        });
        try {
            executor.execute(task);
            long remaining = timeout - (System.nanoTime() - start);
            if (remaining <= 0) throw new TimeoutException();
            ScriptResult result = task.get(remaining, TimeUnit.NANOSECONDS);
            // 记录含排队、实例化、绑定和结果读取的实际耗时。
            return new ScriptResult(result.ruleId(), result.scriptMd5(), result.returnValue(), result.result(), elapsed(start), true, result.error());
        } catch (RejectedExecutionException e) {
            task.cancel(true);
            return failure(compiled, start, "EXECUTOR_OVERLOADED");
        } catch (TimeoutException e) {
            task.cancel(true);
            return failure(compiled, start, "SCRIPT_TIMEOUT");
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return failure(compiled, start, "INTERRUPTED");
        } catch (ExecutionException e) {
            // 不将脚本异常消息暴露给调用者；也不捕获吞掉 JVM 致命错误。
            Throwable cause = e.getCause();
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath death) throw death;
            return failure(compiled, start, "SCRIPT_ERROR");
        }
    }

    private ScriptResult failure(CompiledRule rule, long start, String error) {
        return new ScriptResult(rule.rule().ruleId(), rule.digest(), null, null, elapsed(start), true, error);
    }

    private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000.0; }
}
