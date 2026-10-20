package com.risk.gateway.logging;

import com.risk.gateway.Models.DecisionContext;
import io.micrometer.core.instrument.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.*;

/** 最佳努力内存队列；进程崩溃会丢失待写日志，不是审计级持久化。 */
@Component
@ConditionalOnProperty(name="risk.rules.enabled", havingValue="true", matchIfMissing=true)
public class DecisionLogger implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DecisionLogger.class);
    private record Entry(String key, DecisionContext context) {}
    private final BlockingQueue<Entry> queue;
    private final DecisionLogSink sink;
    private final int maxAttempts;
    private final long shutdownMs;
    private final Counter accepted, written, failures, dropped;
    private volatile boolean running;
    private volatile Thread worker;
    private volatile long stopDeadline = Long.MAX_VALUE;

    public DecisionLogger(DecisionLogSink sink, MeterRegistry registry,
        @Value("${risk.log-queue-size:10000}") int capacity,
        @Value("${risk.logs.max-attempts:3}") int maxAttempts,
        @Value("${risk.logs.shutdown-ms:5000}") long shutdownMs) {
        if (capacity < 1 || maxAttempts < 1 || maxAttempts > 10 || shutdownMs < 1 || shutdownMs > 30000)
            throw new IllegalArgumentException("日志队列或重试/关闭配置不合法");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.sink=sink; this.maxAttempts=maxAttempts; this.shutdownMs=shutdownMs;
        accepted=registry.counter("risk.decision.log.accepted");
        written=registry.counter("risk.decision.log.written");
        failures=registry.counter("risk.decision.log.failures");
        dropped=registry.counter("risk.decision.log.dropped");
        Gauge.builder("risk.decision.log.queue.size", queue, BlockingQueue::size).register(registry);
    }

    public synchronized boolean logAsync(DecisionContext context) {
        if (!running || !queue.offer(new Entry(UUID.randomUUID().toString(), context))) {
            dropped.increment();
            // 不记录订单载荷和盈亏；丢失须通过指标告警。
            return false;
        }
        accepted.increment();
        return true;
    }

    private void consume() {
        boolean inFlight = false;
        try {
            while ((running || !queue.isEmpty()) && System.nanoTime() < stopDeadline) {
                Entry entry = queue.poll(100, TimeUnit.MILLISECONDS);
                if (entry == null) continue;
                inFlight = true;
                boolean success = false;
                for (int attempt=1; attempt<=maxAttempts; attempt++) {
                    try { sink.write(entry.key(), entry.context()); written.increment(); success=true; break; }
                    catch (RuntimeException e) {
                        failures.increment();
                        log.error("决策日志写入失败 logKey={}, attempt={}", entry.key(), attempt, e);
                        if (attempt < maxAttempts && System.nanoTime() < stopDeadline) Thread.sleep(50L * attempt);
                        else break;
                    }
                }
                if (!success) dropped.increment();
                inFlight = false;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            running = false;
            if (inFlight) dropped.increment();
            int abandoned = queue.size();
            queue.clear();
            dropped.increment(abandoned);
            if (abandoned > 0) log.error("日志消费者停止，丢弃排队日志 count={}", abandoned);
        }
    }

    @Override public synchronized void start() {
        if (running) return;
        if (worker != null && worker.isAlive()) throw new IllegalStateException("旧日志线程仍在关闭中");
        running=true; stopDeadline=Long.MAX_VALUE;
        worker=new Thread(this::consume, "decision-log-writer");
        worker.setDaemon(true); worker.start();
    }
    @Override public void stop() {
        Thread current;
        synchronized (this) {
            running=false;
            stopDeadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(shutdownMs);
            current=worker;
        }
        if (current != null) {
            try { current.join(shutdownMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (current.isAlive()) {
                current.interrupt();
                log.error("日志关闭等待超时；JDBC 调用可能不能被中断，待写日志无法保证保存");
            }
        }
    }
    @Override public void stop(Runnable callback) { try { stop(); } finally { callback.run(); } }
    @Override public boolean isRunning() { return running; }
    // 低 phase：晚于风险请求线程池关闭，尽量接收完成中的订单日志。
    @Override public int getPhase() { return -100; }
}
