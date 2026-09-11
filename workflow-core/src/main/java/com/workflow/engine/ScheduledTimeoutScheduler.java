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
 *  - 恢复:注册表本身不持久化(重启即空)。进程重启后由引擎扫描仍 PENDING 的任务,
 *    按 {@code createTime + 节点超时配置} 重算到期时刻重新注册；已过期的立即触发
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
    public void schedule(String taskId, String instanceId, long dueAt,
                         TimeoutPolicy policy, String targetUserId) {
        if (dueAt <= 0 || policy == null || policy == TimeoutPolicy.NONE) {
            return; // 未配置超时
        }
        // 绝对到期时刻 → 相对延时。已过期的任务(delay=0)在调度线程立即触发，
        // 这是进程重启后"扫描到早已过期的任务"的补偿路径。
        long delay = Math.max(0L, dueAt - System.currentTimeMillis());
        ScheduledFuture<?> future = executor.schedule(() -> {
            tasks.remove(taskId);
            log.info("[超时] 任务 {} 超时触发 policy={} target={}",
                    taskId, policy, targetUserId);
            try {
                callback.onTimeout(taskId, instanceId, policy, targetUserId);
            } catch (Exception e) {
                log.error("[超时] 任务 {} 超时回调执行失败: {}", taskId, e.getMessage(), e);
            }
        }, delay, TimeUnit.MILLISECONDS);
        tasks.put(taskId, future);
        log.debug("[超时] 注册任务 {} 到期时刻={} (延时 {}ms) policy={}",
                taskId, dueAt, delay, policy);
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
