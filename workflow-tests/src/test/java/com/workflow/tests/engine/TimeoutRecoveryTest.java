package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.TimeoutScheduler;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.tests.EngineTestBase;
import com.workflow.tests.support.Await;
import com.workflow.tests.support.RecordingTimeoutScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时调度「重启恢复」测试。
 *
 * <p>缺口：调度器注册表在内存里，进程重启即空 —— 重启前建立的待办会<b>静默地</b>
 * 永不超时，审批卡死且无人知晓。修复后引擎在 {@code build()} 时扫描仍 PENDING 的任务，
 * 按 {@code createTime + 节点超时配置} 重算到期时刻重新注册，已过期的立即触发。
 *
 * <p>断言策略：能直接断言「引擎交给调度器的到期时刻」时就不靠睡眠猜时间
 * （{@link RecordingTimeoutScheduler}），只在验证端到端触发时才用真调度器 + 轮询。
 */
class TimeoutRecoveryTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

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
