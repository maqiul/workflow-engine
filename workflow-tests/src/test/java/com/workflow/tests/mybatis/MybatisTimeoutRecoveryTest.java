package com.workflow.tests.mybatis;

import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.TimeoutScheduler;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.tests.MybatisEngineTestBase;
import com.workflow.tests.support.Await;
import com.workflow.tests.support.RecordingTimeoutScheduler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis-Plus 仓储下的超时调度「重启恢复」测试 —— 与 InMemory 共用同一组用例。
 *
 * <p>对持久化层的额外意义：恢复算出的到期时刻是 {@code createTime + 节点超时配置}，
 * 其中 {@code createTime} 必须真的从 {@code wf_task.create_time} 读回来。若某套仓储
 * 在读回时丢掉创建时间，恢复就会算错到期时刻 —— 这组用例正是那个洞的探测器。
 */
class MybatisTimeoutRecoveryTest extends MybatisEngineTestBase {

    // ========== 辅助 ==========

    /** 重启后的引擎：同一批仓储 + 新调度器（注册表为空，等价于重启后状态） */
    private WorkflowEngine engineWith(TimeoutScheduler scheduler, boolean autoRecover) {
        return WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .timeoutScheduler(scheduler)
                .autoRecoverTimeouts(autoRecover)
                .build();
    }

    /** 真调度器（线程池）的引擎，用于验证端到端真实触发 */
    private WorkflowEngine engineWithDefaultScheduler() {
        return WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
    }

    /** 单节点用户任务流程：review 配超时 */
    private void timeoutProcess(String key, long timeoutMillis, TimeoutPolicy policy) {
        ProcessDefinition def = simple(key)
                .start("start")
                .userTask("review", "审批", any("user1"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .timeout("review", timeoutMillis, policy)
                .build();
        register(def);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", ex);
        }
    }

    // ========== 用例 ==========

    @Test
    void restartedEngine_shouldRegisterSameDueAtAsOriginal() {
        timeoutProcess("restart-same-due", 400, TimeoutPolicy.AUTO_APPROVE);

        // 重启前：建任务时算出的到期时刻
        RecordingTimeoutScheduler before = new RecordingTimeoutScheduler();
        String instanceId = engineWith(before, false).start("restart-same-due", null);
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();
        RecordingTimeoutScheduler.Registration original = before.onlyRegistration();
        assertThat(original.dueAt())
                .as("建任务时的到期时刻 = createTime + 节点超时配置")
                .isEqualTo(taskRepo.findById(taskId).getCreateTime() + 400);

        // 重启后：恢复扫描必须重算出<b>同一个</b>到期时刻（dueAt 是派生的，不落库）
        RecordingTimeoutScheduler after = new RecordingTimeoutScheduler();
        engineWith(after, true);

        RecordingTimeoutScheduler.Registration recovered = after.onlyRegistration();
        assertThat(recovered.taskId()).isEqualTo(taskId);
        assertThat(recovered.instanceId()).isEqualTo(instanceId);
        assertThat(recovered.dueAt()).isEqualTo(original.dueAt());
        assertThat(recovered.policy()).isEqualTo(TimeoutPolicy.AUTO_APPROVE);
        assertThat(after.registrations()).hasSize(1);
    }

    @Test
    void overdueTask_shouldBeRegisteredAsAlreadyExpired() {
        timeoutProcess("restart-overdue", 100, TimeoutPolicy.AUTO_APPROVE);

        RecordingTimeoutScheduler before = new RecordingTimeoutScheduler();
        String instanceId = engineWith(before, false).start("restart-overdue", null);
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();

        // 模拟"停机期间任务已经过期"：等过到期时刻再重启
        sleep(150);
        long restartAt = System.currentTimeMillis();

        RecordingTimeoutScheduler after = new RecordingTimeoutScheduler();
        engineWith(after, true);

        RecordingTimeoutScheduler.Registration recovered = after.onlyRegistration();
        assertThat(recovered.taskId()).isEqualTo(taskId);
        assertThat(recovered.dueAt())
                .as("恢复时到期时刻已是过去时间 —— 调度器据此立即触发（补偿路径）")
                .isLessThan(restartAt);
    }

    @Test
    void restartedEngine_shouldActuallyCompleteTask() {
        timeoutProcess("restart-fire", 200, TimeoutPolicy.AUTO_APPROVE);

        WorkflowEngine first = engineWithDefaultScheduler();
        String instanceId = first.start("restart-fire", null);
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();
        first.shutdown();  // 进程退出：注册表随内存一起消失

        WorkflowEngine restarted = engineWithDefaultScheduler();
        Await.until(() -> restarted.getInstance(instanceId).getStatus() == InstanceStatus.COMPLETED, 5000);

        assertThat(taskRepo.findById(taskId).getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(taskRepo.findById(taskId).getCompletedApprovers()).contains("__system__");
    }

    @Test
    void completedInstance_shouldNotBeRecovered() {
        timeoutProcess("restart-done", 4000, TimeoutPolicy.AUTO_APPROVE);

        RecordingTimeoutScheduler before = new RecordingTimeoutScheduler();
        WorkflowEngine first = engineWith(before, false);
        String instanceId = first.start("restart-done", null);
        String taskId = taskRepo.findByInstanceId(instanceId).get(0).getId();
        first.completeTask(taskId, "user1", true);
        assertThat(first.getInstance(instanceId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);

        RecordingTimeoutScheduler after = new RecordingTimeoutScheduler();
        WorkflowEngine restarted = engineWith(after, true);

        assertThat(after.registrations()).isEmpty();
        assertThat(restarted.recoverTimeouts()).isZero();
    }

    @Test
    void nodeWithoutTimeout_shouldNotBeRecovered() {
        register(simple("restart-no-timeout")
                .start("start")
                .userTask("review", "审批", any("user1"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build());

        RecordingTimeoutScheduler before = new RecordingTimeoutScheduler();
        engineWith(before, false).start("restart-no-timeout", null);
        assertThat(before.registrations()).as("节点没配超时，建任务时就不该注册").isEmpty();

        RecordingTimeoutScheduler after = new RecordingTimeoutScheduler();
        WorkflowEngine restarted = engineWith(after, true);

        assertThat(after.registrations()).isEmpty();
        assertThat(restarted.recoverTimeouts()).isZero();
    }

    @Test
    void suspendedInstance_shouldNotBeRecovered() {
        timeoutProcess("restart-suspended", 4000, TimeoutPolicy.AUTO_APPROVE);

        RecordingTimeoutScheduler before = new RecordingTimeoutScheduler();
        WorkflowEngine first = engineWith(before, false);
        String instanceId = first.start("restart-suspended", null);
        first.suspend(instanceId);

        RecordingTimeoutScheduler after = new RecordingTimeoutScheduler();
        WorkflowEngine restarted = engineWith(after, true);

        assertThat(after.registrations())
                .as("挂起的实例不该被恢复调度 —— 否则挂起形同虚设")
                .isEmpty();
        assertThat(restarted.recoverTimeouts()).isZero();
    }

    @Test
    void autoRecoverTimeouts_disabled_shouldSkipScan() {
        timeoutProcess("restart-manual", 4000, TimeoutPolicy.AUTO_APPROVE);

        RecordingTimeoutScheduler before = new RecordingTimeoutScheduler();
        engineWith(before, false).start("restart-manual", null);

        RecordingTimeoutScheduler after = new RecordingTimeoutScheduler();
        WorkflowEngine restarted = engineWith(after, false);

        assertThat(after.registrations()).as("关掉自动恢复后 build() 不该扫描仓储").isEmpty();
        assertThat(restarted.recoverTimeouts())
                .as("手动入口仍可用，返回恢复的调度数量")
                .isEqualTo(1);
    }
}
