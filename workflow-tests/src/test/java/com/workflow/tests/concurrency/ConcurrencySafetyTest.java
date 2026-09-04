package com.workflow.tests.concurrency;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.TimeoutScheduler;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.AuditEventType;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.AuditLog;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.runtime.Token;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 正确性红灯测试 —— 并发安全与事务原子性。
 *
 * <p>这组用例刻意在 v3.6.0 基线上<b>应当失败</b>，用于把「无锁 / 无事务」两个缺陷
 * 钉成可回归的断言。修复完成后它们必须常绿。
 *
 * <p>覆盖三个缺陷：
 * <ol>
 *   <li>{@link #concurrentAllSignShouldCompleteExactlyOnce()} —— 会签并发丢失更新 / 重复推进</li>
 *   <li>{@link #concurrentCompleteAndRejectShouldKeepInstanceConsistent()} —— 通过 vs 驳回竞态</li>
 *   <li>{@link #auditFailureShouldNotLeaveHalfCompletedState()} —— 缺少事务边界</li>
 * </ol>
 */
@DisplayName("P0 并发与事务安全")
class ConcurrencySafetyTest {

    /** 每轮竞态用例重复次数 —— 竞态窗口极小，靠重复次数放大命中概率 */
    private static final int ROUNDS = 30;
    /** 单轮等待上限 */
    private static final long AWAIT_SECONDS = 10;

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** 不启线程的调度器，避免超时任务干扰并发断言 */
    private static TimeoutScheduler noopScheduler() {
        return new TimeoutScheduler() {
            @Override
            public void schedule(String taskId, String instanceId, long timeoutMillis,
                                 TimeoutPolicy policy, String targetUserId) { }
            @Override
            public void cancel(String taskId) { }
            @Override
            public void shutdown() { }
        };
    }

    // ==================== B1: 会签并发 ====================

    @Test
    @DisplayName("B1 两名审批人同时通过 ALL 会签任务，不应丢失更新或重复推进 Token")
    void concurrentAllSignShouldCompleteExactlyOnce() throws Exception {
        ProcessDefinition def = ProcessBuilder.create("cc-all")
                .start("start")
                .userTask("review", "会签", Candidate.ofAll("u1", "u2"))
                .userTask("hr", "人事确认", Candidate.ofAny("u3"))
                .end("end")
                .connect("start", "review")
                .connect("review", "hr")
                .connect("hr", "end")
                .build();
        procRepo.save(def);

        List<Throwable> errors = new CopyOnWriteArrayList<>();
        AtomicInteger taskCompleted = new AtomicInteger();
        AtomicInteger hrPending = new AtomicInteger();
        AtomicInteger approverSize2 = new AtomicInteger();

        for (int round = 0; round < ROUNDS; round++) {
            WorkflowEngine engine = newWorkflow(null);
            String instanceId = engine.start("cc-all", Map.of());
            String taskId = pendingTaskOf(instanceId, "review").getId();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch fire = new CountDownLatch(1);

            java.util.function.Function<String, Runnable> action = user -> () -> {
                ready.countDown();
                try {
                    fire.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                    engine.completeTask(taskId, user, true);
                } catch (Throwable t) {
                    errors.add(t);
                }
            };
            FuturePair pair = submitTwo(action.apply("u1"), action.apply("u2"));
            awaitReady(ready);
            fire.countDown();
            pair.join();

            TaskInstance task = taskRepo.findById(taskId);
            if (task.getStatus() == TaskStatus.COMPLETED) taskCompleted.incrementAndGet();
            if (task.getCompletedApprovers().size() == 2) approverSize2.incrementAndGet();
            long pendingHr = taskRepo.findByInstanceId(instanceId).stream()
                    .filter(t -> "hr".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                    .count();
            if (pendingHr == 1) hrPending.incrementAndGet();
        }

        softAssert(errors, taskCompleted.get(), approverSize2.get(), hrPending.get(), ROUNDS);
    }

    // ==================== B2: 通过 vs 驳回竞态 ====================

    @Test
    @DisplayName("B2 一人通过 + 一人驳回同一会签任务，实例终态必须自洽")
    void concurrentCompleteAndRejectShouldKeepInstanceConsistent() throws Exception {
        ProcessDefinition def = ProcessBuilder.create("cc-race")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u0"))
                .userTask("review", "会签", Candidate.ofAll("u1", "u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build();
        procRepo.save(def);

        List<Throwable> fatal = new CopyOnWriteArrayList<>();
        List<String> benign = new CopyOnWriteArrayList<>();
        List<String> bothRejected = new ArrayList<>();
        List<String> inconsistencies = new ArrayList<>();

        for (int round = 0; round < ROUNDS; round++) {
            WorkflowEngine engine = newWorkflow(null);
            String instanceId = engine.start("cc-race", Map.of());
            // 先过掉 apply
            engine.completeTask(pendingTaskOf(instanceId, "apply").getId(), "u0", true);
            String taskId = pendingTaskOf(instanceId, "review").getId();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch fire = new CountDownLatch(1);
            List<Throwable> roundErrors = new CopyOnWriteArrayList<>();

            Runnable complete = () -> {
                ready.countDown();
                try {
                    fire.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                    engine.completeTask(taskId, "u1", true);
                } catch (Throwable t) {
                    roundErrors.add(t);
                }
            };
            Runnable reject = () -> {
                ready.countDown();
                try {
                    fire.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                    engine.rejectTask(taskId, "u2", "资料不全");
                } catch (Throwable t) {
                    roundErrors.add(t);
                }
            };
            FuturePair pair = submitTwo(complete, reject);
            awaitReady(ready);
            fire.countDown();
            pair.join();

            for (Throwable t : roundErrors) {
                if (benignRejection(t)) {
                    benign.add("round=" + round + " " + t.getMessage());
                } else {
                    fatal.add(t);
                }
            }
            // 双方都被拒 = 没有一次操作真正生效，属于丢失操作
            if (roundErrors.size() == 2 && roundErrors.stream().allMatch(ConcurrencySafetyTest::benignRejection)) {
                bothRejected.add("round=" + round + " 通过与驳回双双失败，无人改动生效");
            }
            collectInconsistency(instanceId, inconsistencies);
        }

        assertThat(fatal)
                .as("不得出现内部一致性异常（Token 错乱 / 集合并发修改等）")
                .isEmpty();
        assertThat(bothRejected)
                .as("每轮至少一方的操作必须生效")
                .isEmpty();
        assertThat(inconsistencies)
                .as("实例终态必须自洽")
                .isEmpty();
        // 迟到者被明确拒绝是预期行为，记录数量供观察，不作为失败
        System.out.println("[B2] 迟到者被正当拒绝的次数: " + benign.size() + "/" + ROUNDS + " 轮");
    }

    /**
     * 判断异常是否为「正当的业务拒绝」。
     *
     * <p>并发下必然有一方是迟到者，它的前置校验（任务已非 PENDING）理应生效并明确报错，
     * 让调用方知道"这条待办已被他人处理"。这与 {@code ConcurrentModificationException}
     * 或「Token 不存在或已消耗」这类内部状态崩溃有本质区别 —— 后者才是缺陷。
     */
    private static boolean benignRejection(Throwable t) {
        return t instanceof IllegalStateException
                && t.getMessage() != null
                && t.getMessage().startsWith("任务非 PENDING 状态");
    }

    // ==================== B3: 事务原子性 ====================

    @Test
    @DisplayName("B3 写库中途失败应整体回滚，不得留下半完成任务")
    void auditFailureShouldNotLeaveHalfCompletedState() {
        ProcessDefinition def = ProcessBuilder.create("tx-atomic")
                .start("start")
                .userTask("review", "审批", Candidate.ofAny("u1"))
                .userTask("hr", "人事", Candidate.ofAny("u3"))
                .end("end")
                .connect("start", "review")
                .connect("review", "hr")
                .connect("hr", "end")
                .build();
        procRepo.save(def);

        // 审计日志写入抛异常 —— 它发生在 taskRepo.save 之后，正是缺少事务的暴露点
        AuditLogRepository exploding = new AuditLogRepository() {
            @Override
            public void save(AuditLog log) {
                if (log.getEventType() == AuditEventType.TASK_COMPLETED) {
                    throw new IllegalStateException("模拟审计库故障");
                }
            }
            @Override
            public List<AuditLog> findByInstanceId(String instanceId) { return List.of(); }
            @Override
            public List<AuditLog> findByTaskId(String taskId) { return List.of(); }
            @Override
            public List<AuditLog> findByTimeRange(Instant from, Instant to) { return List.of(); }
            @Override
            public void clear() { }
        };

        WorkflowEngine engine = newWorkflow(exploding);
        String instanceId = engine.start("tx-atomic", Map.of());
        String taskId = pendingTaskOf(instanceId, "review").getId();

        Throwable thrown = null;
        try {
            engine.completeTask(taskId, "u1", true);
        } catch (Throwable t) {
            thrown = t;
        }

        assertThat(thrown).as("审计写入失败应当向调用方抛出").isNotNull();

        // 核心断言：失败后实例必须像什么都没发生过
        TaskInstance task = taskRepo.findById(taskId);
        assertThat(task.getStatus())
                .as("事务应回滚任务状态，不能停在已 COMPLETED 的半完成态")
                .isEqualTo(TaskStatus.PENDING);

        ProcessInstance inst = instRepo.findById(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(inst.getActiveTokens()).hasSize(1);
        assertThat(inst.getActiveTokens().values().iterator().next().getCurrentNodeId())
                .as("Token 不应被推进到下一节点")
                .isEqualTo("review");
        assertThat(taskRepo.findByInstanceId(instanceId))
                .as("不应残留已创建的下游任务")
                .filteredOn(t -> "hr".equals(t.getNodeId()))
                .isEmpty();
    }

    // ==================== 公共辅助 ====================

    private WorkflowEngine newWorkflow(AuditLogRepository auditRepo) {
        return WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .timeoutScheduler(noopScheduler())
                .auditLogRepository(auditRepo)
                .build();
    }

    private TaskInstance pendingTaskOf(String instanceId, String nodeId) {
        return taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals(nodeId) && t.getStatus() == TaskStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到 PENDING 任务 node=" + nodeId));
    }

    /** 实例自洽性检查：活跃 Token 唯一，且其所在节点恰有一个 PENDING 任务（END 除外） */
    private void collectInconsistency(String instanceId, List<String> out) {
        ProcessInstance inst = instRepo.findById(instanceId);
        if (inst.getStatus() != InstanceStatus.RUNNING) {
            return; // 已终结的实例不校验待办
        }
        Map<String, Token> tokens = inst.getActiveTokens();
        if (tokens.size() != 1) {
            out.add("activeTokens 应为 1，实际 " + tokens.size() + " (round 实例 " + instanceId + ")");
            return;
        }
        String nodeId = tokens.values().iterator().next().getCurrentNodeId();
        if ("end".equals(nodeId)) {
            out.add("Token 停在 end 但实例仍 RUNNING，未正确终结");
            return;
        }
        long pending = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .count();
        if (pending != 1) {
            out.add("node=" + nodeId + " 但 PENDING 任务数为 " + pending);
        }
    }

    private void softAssert(List<Throwable> errors, int completed, int approvers2,
                            int hrPending, int rounds) {
        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty()) {
            sb.append(String.format("%n泄漏异常 %d 个，首个: %s",
                    errors.size(), errors.get(0)));
        }
        if (completed != rounds) sb.append(String.format("%n任务 COMPLETED 仅 %d/%d 轮", completed, rounds));
        if (approvers2 != rounds) sb.append(String.format("%ncompletedApprovers=2 仅 %d/%d 轮（丢失更新）", approvers2, rounds));
        if (hrPending != rounds) sb.append(String.format("%n下游 hr 恰好 1 个待办仅 %d/%d 轮（Token 推进异常）", hrPending, rounds));
        assertThat(sb.toString())
                .as("并发正确性（%d 轮）", rounds)
                .isEmpty();
    }

    // ==================== 线程编排 ====================

    private record FuturePair(java.util.concurrent.Future<?> a, java.util.concurrent.Future<?> b) {
        void join() throws Exception {
            a.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            b.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private FuturePair submitTwo(Runnable first, Runnable second) {
        return new FuturePair(pool.submit(first), pool.submit(second));
    }

    private void awaitReady(CountDownLatch ready) throws InterruptedException {
        assertThat(ready.await(AWAIT_SECONDS, TimeUnit.SECONDS))
                .as("两个线程应同时就位")
                .isTrue();
    }
}
