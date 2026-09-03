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
}
