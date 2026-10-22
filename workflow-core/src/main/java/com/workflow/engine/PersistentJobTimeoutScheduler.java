package com.workflow.engine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.workflow.enums.JobType;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.runtime.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 持久化版超时调度器 —— 把「任务超时」变成一种持久化作业。
 *
 * <p>与 {@link ScheduledTimeoutScheduler}（内存版）的关系：
 * <ul>
 *   <li>接口完全相同（{@link TimeoutScheduler}），所以<b>引擎 30+ 调用点零改动</b>，
 *       换实现只是换一行注入；</li>
 *   <li>内存版把回调丢进 JDK 定时器，回调抛异常就<b>永久丢失</b>（只打一行 error log），
 *       且重启要靠「扫描任务 + 重算到期时刻」做派生式恢复；</li>
 *   <li>本实现把超时写成 {@code wf_job} 的一行，由 {@link JobExecutor} 领取执行：
 *       失败自动重试、耗尽进死信可查、重启后作业仍在（不必再扫描重算）。</li>
 * </ul>
 *
 * <p>作业 id = taskId：这样"任务完成/驳回/转办时取消超时"直接按 taskId 删作业，
 * 与内存版「按 taskId 取消 future」语义一致，且天然幂等（重复注册同一 taskId 覆盖一行）。
 */
public class PersistentJobTimeoutScheduler implements TimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(PersistentJobTimeoutScheduler.class);

    private final JobExecutor executor;
    private final ScheduledTimeoutScheduler.TimeoutCallback callback;

    /**
     * @param executor 作业执行器（负责领取与执行）
     * @param callback 超时回调（通常是 {@code timeoutHandler::onTaskTimeout}）
     */
    public PersistentJobTimeoutScheduler(JobExecutor executor,
                                         ScheduledTimeoutScheduler.TimeoutCallback callback) {
        this.executor = executor;
        this.callback = callback;
        // 超时作业的执行：解出策略与目标用户，回调给引擎（引擎内部再走流程树锁）
        executor.register(JobType.TIMEOUT, this::onTimeoutJob);
    }

    @Override
    public void schedule(String taskId, String instanceId, long dueAt,
                         TimeoutPolicy policy, String targetUserId) {
        if (dueAt <= 0 || policy == null || policy == TimeoutPolicy.NONE) {
            return;   // 未配置超时
        }
        JSONObject payload = new JSONObject();
        payload.put("taskId", taskId);
        payload.put("instanceId", instanceId);
        payload.put("policy", policy.name());
        if (targetUserId != null) {
            payload.put("targetUserId", targetUserId);
        }
        executor.submit(taskId, JobType.TIMEOUT, instanceId, payload.toJSONString(), dueAt);
        log.debug("[超时作业] 注册 task={} dueAt={} policy={}", taskId, dueAt, policy);
    }

    @Override
    public void cancel(String taskId) {
        // 作业 id 就是 taskId；不存在时删除也不报错（幂等）
        executor.cancel(taskId);
        log.debug("[超时作业] 取消 task={}", taskId);
    }

    @Override
    public void shutdown() {
        // 执行器的生命周期由它自己管理（应用负责 close）——这里不能替它关，
        // 否则一个调度器被关掉会连带停掉整个作业执行器。
    }

    /** 作业执行入口：解 payload → 回调引擎 */
    private void onTimeoutJob(Job job) {
        Map<String, Object> payload = JSON.parseObject(job.getPayload());
        String taskId = str(payload, "taskId");
        String instanceId = str(payload, "instanceId");
        String policyName = str(payload, "policy");
        String targetUserId = str(payload, "targetUserId");
        if (taskId == null || policyName == null) {
            throw new IllegalStateException("超时作业参数不完整: " + job.getPayload());
        }
        TimeoutPolicy policy;
        try {
            policy = TimeoutPolicy.valueOf(policyName);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("未知的超时策略: " + policyName, e);
        }
        log.info("[超时作业] 触发 task={} policy={} target={}", taskId, policy, targetUserId);
        callback.onTimeout(taskId, instanceId, policy, targetUserId);
    }

    private static String str(Map<String, Object> payload, String key) {
        Object v = payload == null ? null : payload.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
