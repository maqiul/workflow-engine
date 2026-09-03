package com.workflow.engine;

import com.workflow.enums.TimeoutPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 默认超时调度器 - 基于 JDK ScheduledExecutorService 的自研实现
 *
 * 设计要点:
 *  - 线程池:单线程 daemon 调度线程(核心 1,可扩),不阻塞应用退出
 *  - 回调:超时后在调度线程执行 {@link TimeoutCallback#onTimeout},引擎据此按策略动作
 *  - 幂等:即使 cancel 竞态(回调已入队),回调内部会重查任务状态,非 PENDING 则忽略
 *  - 内存:schedule/cancel 都更新 tasks map,避免已取消任务的未来引用泄漏
 */
public class ScheduledTimeoutScheduler implements TimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTimeoutScheduler.class);

    /** 超时回调 - 由引擎注入 */
    public interface TimeoutCallback {
        void onTimeout(String taskId, String instanceId, TimeoutPolicy policy, String targetUserId);
    }

    private final ScheduledExecutorService executor;
    private final TimeoutCallback callback;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();

    public ScheduledTimeoutScheduler(TimeoutCallback callback) {
        this.callback = callback;
        ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "workflow-timeout");
            t.setDaemon(true);
            return t;
        });
        pool.setRemoveOnCancelPolicy(true);
        this.executor = pool;
    }

    @Override
    public void schedule(String taskId, String instanceId, long timeoutMillis,
                         TimeoutPolicy policy, String targetUserId) {
        if (timeoutMillis <= 0 || policy == null || policy == TimeoutPolicy.NONE) {
            return; // 未配置超时
        }
        ScheduledFuture<?> future = executor.schedule(() -> {
            tasks.remove(taskId);
            log.info("[超时] 任务 {} 超时触发 policy={} target={}",
                    taskId, policy, targetUserId);
            try {
                callback.onTimeout(taskId, instanceId, policy, targetUserId);
            } catch (Exception e) {
                log.error("[超时] 任务 {} 超时回调执行失败: {}", taskId, e.getMessage(), e);
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        tasks.put(taskId, future);
        log.debug("[超时] 注册任务 {} 超时 {}ms policy={}", taskId, timeoutMillis, policy);
    }

    @Override
    public void cancel(String taskId) {
        ScheduledFuture<?> future = tasks.remove(taskId);
        if (future != null) {
            future.cancel(false);
            log.debug("[超时] 取消任务 {} 的超时调度", taskId);
        }
    }

    @Override
    public void shutdown() {
        executor.shutdownNow();
        tasks.clear();
    }
}
