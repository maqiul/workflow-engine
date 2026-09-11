package com.workflow.tests.support;

import com.workflow.engine.TimeoutScheduler;
import com.workflow.enums.TimeoutPolicy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 只记录、不触发的超时调度器。
 *
 * <p>用途有二：
 * <ul>
 *   <li>断言引擎交给调度器的<b>到期时刻</b> —— 比睡眠若干毫秒去猜触发时间稳定得多；</li>
 *   <li>充当「注册表为空」的调度器，等价于进程重启后的调度器状态。</li>
 * </ul>
 */
public class RecordingTimeoutScheduler implements TimeoutScheduler {

    /** 一次注册的快照 */
    public record Registration(String taskId, String instanceId, long dueAt,
                               TimeoutPolicy policy, String targetUserId) { }

    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final List<String> cancellations = new CopyOnWriteArrayList<>();

    @Override
    public void schedule(String taskId, String instanceId, long dueAt,
                         TimeoutPolicy policy, String targetUserId) {
        registrations.add(new Registration(taskId, instanceId, dueAt, policy, targetUserId));
    }

    @Override
    public void cancel(String taskId) {
        cancellations.add(taskId);
    }

    @Override
    public void shutdown() {
        // 不持有线程或资源
    }

    public List<Registration> registrations() {
        return List.copyOf(registrations);
    }

    public List<String> cancellations() {
        return List.copyOf(cancellations);
    }

    /** 唯一一次注册；没有注册或多次注册都直接失败，并把实际内容带进错误信息 */
    public Registration onlyRegistration() {
        List<Registration> all = registrations();
        if (all.size() != 1) {
            throw new AssertionError("期望恰好 1 次超时注册，实际 " + all.size() + " 次: " + all);
        }
        return all.get(0);
    }
}
