package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.concurrency.LocalInstanceLocks;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.TimeoutScheduler;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.repository.HistoryRepository;
import com.workflow.repository.InMemoryHistoryRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.HistoricActivityInstance;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.support.FlakyTaskRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史活动：三套仓储必须给出同一答案，且业务回滚不得留下幽灵活动。
 *
 * <p>坚持跑三遍是这几轮换来的教训 —— 能力只要只在内存版实现过，就等于没实现：
 * 真实数据库上一调用即 {@code UnsupportedOperationException}，而内存测试全绿、
 * 编译器也不报错，这类缺口已经踩过两次。
 */
@DisplayName("历史活动跨仓储一致性与回滚")
class HistoryConsistencyTest {

    private static JpaPersistence jpa;
    private static MybatisPersistence mb;

    @BeforeAll
    static void startDatabases() {
        jpa = JpaPersistence.getDefault();
        jpa.init();
        mb = MybatisPersistence.getDefault();
        mb.init();
    }

    private record Suite(String label, ProcessRepository procRepo, InstanceRepository instRepo,
                         TaskRepository taskRepo, HistoryRepository histRepo) { }

    private static final TimeoutScheduler NOOP_SCHEDULER = new TimeoutScheduler() {
        @Override
        public void schedule(String t, String i, long ms, TimeoutPolicy p, String u) { }
        @Override
        public void cancel(String t) { }
        @Override
        public void shutdown() { }
    };

    private Suite suite(String which) {
        ProcessRepository pr;
        InstanceRepository ir;
        TaskRepository tr;
        HistoryRepository hr;
        switch (which) {
            case "JPA" -> {
                jpa.inTransaction(em -> {
                    em.createNativeQuery("DELETE FROM wf_hist_task").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_hist_activity").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_task").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_token").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_instance").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_process_def").executeUpdate();
                    return null;
                });
                pr = jpa.processRepo();
                ir = jpa.instanceRepo();
                tr = jpa.taskRepo();
                hr = jpa.historyRepo();
            }
            case "MyBatis" -> {
                mb.clearTables();
                pr = mb.processRepo();
                ir = mb.instanceRepo();
                tr = mb.taskRepo();
                hr = mb.historyRepo();
            }
            default -> {
                pr = new InMemoryProcessRepository();
                ir = new InMemoryInstanceRepository();
                tr = new InMemoryTaskRepository();
                hr = new InMemoryHistoryRepository();
            }
        }
        return new Suite(which, pr, ir, tr, hr);
    }

    private void registerTwoStep(Suite s) {
        ProcessDefinition def = ProcessBuilder.create("hist-cons")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .userTask("manager", "经理审批", Candidate.ofAny("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();
        s.procRepo().save(def);
    }

    private String pendingTask(Suite s, String instanceId, String nodeId) {
        return s.taskRepo().findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals(nodeId) && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow(() -> new AssertionError(s.label() + " 无待办 " + nodeId)).getId();
    }

    private WorkflowEngine engineOf(Suite s) {
        return new WorkflowEngine(s.procRepo(), s.instRepo(), s.taskRepo(), NOOP_SCHEDULER,
                null, null, null, null, s.histRepo(), new LocalInstanceLocks(), null, 0, 0L);
    }

    private static final List<String> ALL = List.of("InMemory", "JPA", "MyBatis");

    // ---------- 1. 路径顺序与闭合 ----------

    @Test
    @DisplayName("三套仓储都还原出正确的节点顺序，且全部闭合")
    void pathOrderAndClosureMatchAcrossRepositories() throws Exception {
        for (String which : ALL) {
            Suite s = suite(which);
            registerTwoStep(s);
            WorkflowEngine engine = engineOf(s);

            String id = engine.start("hist-cons", Map.of());
            Thread.sleep(15);
            engine.completeTask(pendingTask(s, id, "apply"), "u1", true);
            Thread.sleep(15);
            engine.completeTask(pendingTask(s, id, "manager"), "u2", true);

            List<HistoricActivityInstance> acts = s.histRepo().findByInstanceId(id);
            assertThat(acts).extracting(HistoricActivityInstance::getActivityId)
                    .as("%s: 路径顺序必须确定，不能靠随机 id 兜底", which)
                    .containsExactly("start", "apply", "manager", "end");
            assertThat(acts).as("%s: 全部活动应已闭合", which)
                    .noneMatch(HistoricActivityInstance::isOpen);
            assertThat(acts).as("%s: duration 可计算", which)
                    .allSatisfy(a -> assertThat(a.getDuration()).isNotNull());
            engine.shutdown();
        }
    }

    // ---------- 2. UserTask 等待时长 ----------

    @Test
    @DisplayName("三套仓储的 UserTask 耗时都覆盖人类等待时长")
    void userTaskWaitRecordedOnEveryRepository() throws Exception {
        for (String which : ALL) {
            Suite s = suite(which);
            registerTwoStep(s);
            WorkflowEngine engine = engineOf(s);

            String id = engine.start("hist-cons", Map.of());
            Thread.sleep(60);
            engine.completeTask(pendingTask(s, id, "apply"), "u1", true);

            HistoricActivityInstance apply = s.histRepo().findByInstanceId(id).stream()
                    .filter(a -> "apply".equals(a.getActivityId()))
                    .findFirst().orElseThrow();
            assertThat(apply.getDuration())
                    .as("%s: 等待时长必须落进历史，否则效能报表全失真", which)
                    .isGreaterThanOrEqualTo(60L);
            engine.shutdown();
        }
    }

    // ---------- 3. 平均值聚合 ----------

    @Test
    @DisplayName("三套仓储的平均耗时聚合结果一致且只含已闭合活动")
    void averageAgreement() {
        for (String which : ALL) {
            Suite s = suite(which);
            registerTwoStep(s);
            WorkflowEngine engine = engineOf(s);

            String a = engine.start("hist-cons", Map.of());
            engine.completeTask(pendingTask(s, a, "apply"), "u1", true);
            String b = engine.start("hist-cons", Map.of());   // apply 仍 open

            OptionalDouble avg = s.histRepo().averageClosedDuration("hist-cons", "apply");
            assertThat(avg).as("%s: 有一个已闭合样本就应有平均值", which).isPresent();
            assertThat(avg.getAsDouble()).as("%s: 平均值不该为负", which).isGreaterThanOrEqualTo(0d);

            List<HistoricActivityInstance> applyActs =
                    s.histRepo().findByActivity("hist-cons", "apply");
            assertThat(applyActs).as("%s: 两个实例各一条 apply 活动", which).hasSize(2);
            assertThat(applyActs.stream().filter(HistoricActivityInstance::isOpen).count())
                    .as("%s: 未闭合的那条不参与平均值", which).isEqualTo(1);
            engine.shutdown();
        }
    }

    // ---------- 4. 回滚不留幽灵活动 ----------

    @Test
    @DisplayName("业务写入中途失败时，历史活动必须一起回滚")
    void rollbackLeavesNoGhostActivity() {
        for (String which : ALL) {
            Suite s = suite(which);
            registerTwoStep(s);

            // 先正常发起：历史里应留下 START(闭合) + APPLY(进行中)
            WorkflowEngine engine = engineOf(s);
            String id = engine.start("hist-cons", Map.of());
            engine.shutdown();

            List<HistoricActivityInstance> baseline = s.histRepo().findByInstanceId(id);
            assertThat(baseline).as("%s: 发起后应有 start(闭合) 与 apply(进行中)", which)
                    .extracting(HistoricActivityInstance::getActivityId)
                    .containsExactly("start", "apply");
            HistoricActivityInstance applyBefore = baseline.stream()
                    .filter(a -> "apply".equals(a.getActivityId())).findFirst().orElseThrow();
            assertThat(applyBefore.isOpen()).as("%s: apply 活动应仍在进行", which).isTrue();

            // 第 2 次 save 落在「apply 已闭合、manager 已开启」之后 —— 正是幽灵活动的成因点
            FlakyTaskRepository flaky = new FlakyTaskRepository(s.taskRepo(), 2);
            WorkflowEngine broken = new WorkflowEngine(s.procRepo(), s.instRepo(), flaky,
                    NOOP_SCHEDULER, null, null, null, null, s.histRepo(),
                    new LocalInstanceLocks(), null, 0, 0L);
            String applyTask = pendingTask(s, id, "apply");

            assertThatThrownBy(() -> broken.completeTask(applyTask, "u1", true))
                    .as("%s: 注入的写失败应当抛出", which)
                    .isInstanceOf(IllegalStateException.class);
            broken.shutdown();

            assertThat(flaky.saveCount())
                    .as("%s: 注入点必须真的被命中，否则本用例什么都没测到", which)
                    .isGreaterThanOrEqualTo(2);

            // 历史必须回到发起后的样子：apply 仍为进行中，manager 一条都不该有
            List<HistoricActivityInstance> after = s.histRepo().findByInstanceId(id);
            assertThat(after).as("%s: 回滚后活动条数不应变化", which)
                    .hasSize(baseline.size());
            assertThat(after).extracting(HistoricActivityInstance::getActivityId)
                    .as("%s: 不应留下 manager 的幽灵活动", which)
                    .containsExactly("start", "apply");
            HistoricActivityInstance applyAfter = after.stream()
                    .filter(a -> "apply".equals(a.getActivityId())).findFirst().orElseThrow();
            assertThat(applyAfter.isOpen())
                    .as("%s: apply 的闭合必须随业务一起回滚，否则报表会显示一个从未发生的审批", which)
                    .isTrue();
            assertThat(applyAfter.getId())
                    .as("%s: 应还是同一条记录，而不是被删后重建", which)
                    .isEqualTo(applyBefore.getId());

            // 业务侧同样回滚
            TaskInstance applyNow = s.taskRepo().findById(applyTask);
            assertThat(applyNow.getStatus()).isEqualTo(TaskStatus.PENDING);
            ProcessInstance inst = s.instRepo().findById(id);
            assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        }
    }

    // ---------- 5. 历史任务：谁批的、怎么结束的 ----------

    @Test
    @DisplayName("三套仓储都留下每个已落定任务的处理人与结束原因")
    void taskHistoryRecordsAssigneeAndReason() throws Exception {
        for (String which : ALL) {
            Suite s = suite(which);
            registerTwoStep(s);
            WorkflowEngine engine = engineOf(s);

            String id = engine.start("hist-cons", Map.of());
            Thread.sleep(20);
            String applyTask = pendingTask(s, id, "apply");
            engine.completeTask(applyTask, "u1", true);
            engine.completeTask(pendingTask(s, id, "manager"), "u2", true);

            List<com.workflow.runtime.HistoricTaskInstance> tasks =
                    s.histRepo().findTasksByInstanceId(id);
            assertThat(tasks).as("%s: 两张落定待办各一条历史", which).hasSize(2);
            assertThat(tasks).extracting(com.workflow.runtime.HistoricTaskInstance::getNodeId)
                    .as("%s: 按结束时间排序应先 apply 后 manager", which)
                    .containsExactly("apply", "manager");

            com.workflow.runtime.HistoricTaskInstance apply = tasks.get(0);
            assertThat(apply.getEndReason()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(apply.getCompletedBy()).containsExactly("u1");
            assertThat(apply.getTaskId()).isEqualTo(applyTask);
            assertThat(apply.getDuration())
                    .as("%s: 任务耗时须覆盖人类等待", which).isGreaterThanOrEqualTo(20L);
            assertThat(apply.involves("u1")).isTrue();
            assertThat(apply.involves("u9")).isFalse();

            // 按人检索：候选人快照与完成人都要能命中
            assertThat(s.histRepo().findTasksInvolving("u1"))
                    .as("%s: u1 应命中 apply（不得把 u1 误配成 u11 之类）", which)
                    .extracting(com.workflow.runtime.HistoricTaskInstance::getTaskId)
                    .containsExactly(applyTask);
            assertThat(s.histRepo().findTasksInvolving("u2"))
                    .as("%s: u2 只命中 manager", which).hasSize(1);
            assertThat(s.histRepo().findTasksInvolving("nobody")).isEmpty();
            assertThat(s.histRepo().averageClosedTaskDuration("hist-cons", "apply"))
                    .as("%s: 有样本就应有平均办理时长", which).isPresent();
            engine.shutdown();
        }
    }

    @Test
    @DisplayName("会签部分完成不得产生多条任务历史")
    void taskHistoryIsIdempotentPerTask() {
        for (String which : ALL) {
            Suite s = suite(which);
            s.procRepo().save(ProcessBuilder.create("hist-sign")
                    .start("start")
                    .userTask("review", "会签", Candidate.ofAll("u1", "u2"))
                    .end("end")
                    .connect("start", "review")
                    .connect("review", "end")
                    .build());
            WorkflowEngine engine = engineOf(s);
            String id = engine.start("hist-sign", Map.of());
            String taskId = pendingTask(s, id, "review");

            engine.completeTask(taskId, "u1", true);   // 部分完成，任务仍 PENDING
            assertThat(s.histRepo().findTasksByInstanceId(id))
                    .as("%s: 未落定的任务不该进历史", which).isEmpty();

            engine.completeTask(taskId, "u2", true);   // 全员完成
            List<com.workflow.runtime.HistoricTaskInstance> tasks =
                    s.histRepo().findTasksByInstanceId(id);
            assertThat(tasks).as("%s: 一张待办只应有一条历史", which).hasSize(1);
            assertThat(tasks.get(0).getCompletedBy())
                    .as("%s: 两位审批人都要留痕", which)
                    .containsExactlyInAnyOrder("u1", "u2");
            assertThat(tasks.get(0).getAssignee())
                    .as("%s: 多人任务没有单一处理人", which).isNull();
            engine.shutdown();
        }
    }

    @Test
    @DisplayName("转办与驳回的结束原因都要如实记录，候选人快照不得被转办改写")
    void taskHistoryRecordsTransferAndReject() {
        for (String which : ALL) {
            Suite s = suite(which);
            registerTwoStep(s);
            WorkflowEngine engine = engineOf(s);

            String id = engine.start("hist-cons", Map.of());
            String applyTask = pendingTask(s, id, "apply");
            engine.transferTask(applyTask, "u1", "carol");

            List<com.workflow.runtime.HistoricTaskInstance> afterTransfer =
                    s.histRepo().findTasksByInstanceId(id);
            assertThat(afterTransfer).as("%s: 被转办的原任务应落历史", which).hasSize(1);
            assertThat(afterTransfer.get(0).getEndReason()).isEqualTo(TaskStatus.TRANSFERRED);
            assertThat(afterTransfer.get(0).getCandidateUsers())
                    .as("%s: 候选人快照须保留原始指派，否则责任链断裂", which)
                    .containsExactly("u1");

            // carol 批掉转办来的新任务
            engine.completeTask(pendingTask(s, id, "apply"), "carol", true);
            assertThat(s.histRepo().findTasksInvolving("carol"))
                    .as("%s: carol 应能查到接手的那张", which).hasSize(1);

            // 驳回 manager
            engine.rejectTask(pendingTask(s, id, "manager"), "u2", "材料不全");
            assertThat(s.histRepo().findTasksByInstanceId(id).stream()
                    .filter(t -> t.getEndReason() == TaskStatus.REJECTED))
                    .as("%s: 驳回须留下 REJECTED 记录", which).hasSize(1);
            engine.shutdown();
        }
    }
}
