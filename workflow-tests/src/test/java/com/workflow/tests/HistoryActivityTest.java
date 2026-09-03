package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.concurrency.LocalInstanceLocks;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.TimeoutScheduler;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.NodeType;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.repository.InMemoryAuditLogRepository;
import com.workflow.repository.InMemoryHistoryRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.HistoricActivityInstance;
import com.workflow.runtime.ProcessInstance;
import com.workflow.tx.UndoLogTransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 历史活动埋点的行为契约（内存版）。
 *
 * <p>本类只覆盖 InMemory。JPA / MyBatis 的实现与跨仓储一致性、以及
 * 「历史写入必须与业务同事务回滚」的验证，随持久层实现一并补齐 ——
 * 在此之前，不得声称历史能力已交付。
 */
@DisplayName("历史活动实例")
class HistoryActivityTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryHistoryRepository histRepo;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        histRepo = new InMemoryHistoryRepository();
    }

    /** 传 null 让引擎用默认（流程树锁 + undo 事务）。 */
    private WorkflowEngine newEngine(boolean withHistory) {
        TimeoutScheduler noop = new TimeoutScheduler() {
            @Override
            public void schedule(String t, String i, long ms, TimeoutPolicy p, String u) { }
            @Override
            public void cancel(String t) { }
            @Override
            public void shutdown() { }
        };
        return new WorkflowEngine(procRepo, instRepo, taskRepo, noop,
                new InMemoryAuditLogRepository(), null, null, null,
                withHistory ? histRepo : null,
                new LocalInstanceLocks(), new UndoLogTransactionRunner(), 0, 0L);
    }

    private void registerSerial(WorkflowEngine engine) {
        ProcessDefinition def = ProcessBuilder.create("hist-serial")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .userTask("manager", "经理审批", Candidate.ofAny("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();
        procRepo.save(def);
    }

    private String pendingTaskId(String instanceId, String nodeId) {
        return taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals(nodeId) && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow(() -> new AssertionError("无待办 node=" + nodeId)).getId();
    }

    @Test
    @DisplayName("跑完流程后每个节点各留一条已闭合活动，顺序即实际路径")
    void recordsWholePathAndClosesAll() {
        WorkflowEngine engine = newEngine(true);
        registerSerial(engine);

        String id = engine.start("hist-serial", Map.of());
        engine.completeTask(pendingTaskId(id, "apply"), "u1", true);
        engine.completeTask(pendingTaskId(id, "manager"), "u2", true);

        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);

        List<HistoricActivityInstance> acts = histRepo.findByInstanceId(id);
        assertThat(acts).extracting(HistoricActivityInstance::getActivityId)
                .as("按开始时间排序即实际走过的路径")
                .containsExactly("start", "apply", "manager", "end");
        assertThat(acts).allSatisfy(a ->
                assertThat(a.isOpen()).as("%s 应已闭合", a.getActivityId()).isFalse());
        assertThat(acts).allSatisfy(a ->
                assertThat(a.getDuration()).as("%s 的 duration 可计算", a.getActivityId()).isNotNull());
        engine.shutdown();
    }

    @Test
    @DisplayName("UserTask 活动的耗时覆盖人类等待时长，而非处理记录的 0 毫秒")
    void userTaskDurationCoversHumanWait() throws Exception {
        WorkflowEngine engine = newEngine(true);
        registerSerial(engine);

        String id = engine.start("hist-serial", Map.of());
        String applyTask = pendingTaskId(id, "apply");

        Thread.sleep(40);
        engine.completeTask(applyTask, "u1", true);

        HistoricActivityInstance apply = histRepo.findByInstanceId(id).stream()
                .filter(a -> "apply".equals(a.getActivityId()))
                .findFirst().orElseThrow();

        assertThat(apply.getDuration())
                .as("一次进入即闭合会产生 ≈0 的假耗时，报表将完全失真")
                .isGreaterThanOrEqualTo(40L);
        engine.shutdown();
    }

    @Test
    @DisplayName("同一次人类等待只留一条活动记录，不被拆成多段")
    void waitIsNotSplitAcrossRepost() {
        WorkflowEngine engine = newEngine(true);
        procRepo.save(ProcessBuilder.create("hist-repost")
                .start("start")
                .userTask("review", "审批", Candidate.ofAll("u1", "u2"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build());

        String id = engine.start("hist-repost", Map.of());
        String taskId = pendingTaskId(id, "review");
        engine.completeTask(taskId, "u1", true);   // 会签部分完成，仍在等待
        engine.completeTask(taskId, "u2", true);   // 全员完成，才闭合

        List<HistoricActivityInstance> reviews = histRepo.findByInstanceId(id).stream()
                .filter(a -> "review".equals(a.getActivityId()))
                .toList();
        assertThat(reviews).as("两次推进不应各开一条").hasSize(1);
        assertThat(reviews.get(0).isOpen()).isFalse();
        assertThat(reviews.get(0).getTaskId())
                .as("应挂上真实待办 id，便于与 wf_task 对应")
                .isEqualTo(taskId);
        engine.shutdown();
    }

    @Test
    @DisplayName("并行网关两条分支各自留下活动记录")
    void parallelBranchesBothRecorded() {
        WorkflowEngine engine = newEngine(true);
        procRepo.save(ProcessBuilder.create("hist-para")
                .start("start")
                .parallelGateway("fork")
                .userTask("r1", "评审1", Candidate.ofAny("u1"))
                .userTask("r2", "评审2", Candidate.ofAny("u2"))
                .parallelGateway("join")
                .end("end")
                .connect("start", "fork")
                .connect("fork", "r1")
                .connect("fork", "r2")
                .connect("r1", "join")
                .connect("r2", "join")
                .connect("join", "end")
                .build());

        String id = engine.start("hist-para", Map.of());
        engine.completeTask(pendingTaskId(id, "r1"), "u1", true);
        engine.completeTask(pendingTaskId(id, "r2"), "u2", true);

        List<HistoricActivityInstance> acts = histRepo.findByInstanceId(id);
        assertThat(acts).extracting(HistoricActivityInstance::getActivityId)
                .contains("fork", "r1", "r2", "join", "end");
        assertThat(acts).noneMatch(HistoricActivityInstance::isOpen);
        assertThat(acts.stream().filter(a -> a.getTokenId() != null).count())
                .as("每条活动都要能定位到所属 Token，否则并行分支无法归因")
                .isEqualTo(acts.size());
        engine.shutdown();
    }

    @Test
    @DisplayName("平均耗时只统计已闭合活动，未闭合的不污染报表")
    void averageExcludesOpenActivities() {
        WorkflowEngine engine = newEngine(true);
        registerSerial(engine);

        String done = engine.start("hist-serial", Map.of());
        engine.completeTask(pendingTaskId(done, "apply"), "u1", true);
        engine.completeTask(pendingTaskId(done, "manager"), "u2", true);

        String waiting = engine.start("hist-serial", Map.of());   // apply 仍 PENDING

        OptionalDouble avg = histRepo.averageClosedDuration("hist-serial", "apply");
        assertThat(avg).as("至少有一个已闭合样本").isPresent();

        List<HistoricActivityInstance> applyActs = histRepo.findByActivity("hist-serial", "apply");
        assertThat(applyActs).hasSize(2);
        long openCount = applyActs.stream().filter(HistoricActivityInstance::isOpen).count();
        assertThat(openCount).as("等待中的实例应留下一条未闭合活动").isEqualTo(1);

        // 未闭合活动不参与平均：否则报表数值会随查询时刻漂移
        double expectedMin = applyActs.stream()
                .filter(a -> !a.isOpen())
                .mapToLong(HistoricActivityInstance::getDuration).min().orElseThrow();
        assertThat(avg.getAsDouble()).isGreaterThanOrEqualTo(expectedMin);
        engine.shutdown();
    }

    @Test
    @DisplayName("不注入历史仓储时完全不产生历史写入")
    void historyDisabledWhenNotInjected() {
        WorkflowEngine engine = newEngine(false);
        registerSerial(engine);

        String id = engine.start("hist-serial", Map.of());
        engine.completeTask(pendingTaskId(id, "apply"), "u1", true);
        engine.completeTask(pendingTaskId(id, "manager"), "u2", true);

        assertThat(engine.getInstance(id).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(histRepo.findByInstanceId(id))
                .as("未启用历史就不该有写入")
                .isEmpty();
        engine.shutdown();
    }

    @Test
    @DisplayName("活动类型沿用 NodeType，报表可直接按类型分组")
    void activityTypeFollowsNodeType() {
        WorkflowEngine engine = newEngine(true);
        registerSerial(engine);
        String id = engine.start("hist-serial", Map.of());
        engine.completeTask(pendingTaskId(id, "apply"), "u1", true);
        engine.completeTask(pendingTaskId(id, "manager"), "u2", true);

        List<HistoricActivityInstance> acts = histRepo.findByInstanceId(id);
        assertThat(acts.get(0).getActivityType()).isEqualTo(NodeType.START);
        assertThat(acts.get(1).getActivityType()).isEqualTo(NodeType.USER_TASK);
        assertThat(acts.get(2).getActivityType()).isEqualTo(NodeType.USER_TASK);
        assertThat(acts.get(3).getActivityType()).isEqualTo(NodeType.END);
        engine.shutdown();
    }

    @Test
    @DisplayName("归档清理只删已闭合活动，进行中的必须保留")
    void cleanupKeepsOpenActivities() {
        WorkflowEngine engine = newEngine(true);
        registerSerial(engine);

        String done = engine.start("hist-serial", Map.of());
        engine.completeTask(pendingTaskId(done, "apply"), "u1", true);
        engine.completeTask(pendingTaskId(done, "manager"), "u2", true);
        engine.start("hist-serial", Map.of());   // 留下一条进行中的 apply 活动

        int before = histRepo.findByActivity("hist-serial", "apply").size();
        // cutoff 取未来时刻：全部已闭合活动都应被清理
        int removed = histRepo.deleteClosedBefore(System.currentTimeMillis() + 60_000);

        assertThat(removed).as("只删已闭合的").isGreaterThan(0);
        List<HistoricActivityInstance> left = histRepo.findByActivity("hist-serial", "apply");
        assertThat(left).as("未闭合活动必须留下，否则实例永远闭不上").isNotEmpty();
        assertThat(left).allMatch(HistoricActivityInstance::isOpen);
        assertThat(before).isEqualTo(2);
        engine.shutdown();
    }
}
