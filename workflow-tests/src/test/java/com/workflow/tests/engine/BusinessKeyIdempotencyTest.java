package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * businessKey 启动幂等（G-01）：同单号已有进行中实例时拒绝重复提交，
 * 但办结/终止后允许同单号重新发起（仅 RUNNING 算冲突）。
 */
@DisplayName("businessKey 启动幂等")
class BusinessKeyIdempotencyTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
        ProcessDefinition def = ProcessBuilder.create("leave", "请假")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        procRepo.save(def);
    }

    private void finish(String instanceId) {
        TaskInstance t = taskRepo.findByInstanceId(instanceId).get(0);
        engine.completeTask(t.getId(), "u1", true);
    }

    @Test
    @DisplayName("同 businessKey 有进行中实例 → 拒绝重复提交")
    void rejectsDuplicateWhileRunning() {
        String first = engine.start("leave", "BK-001", "alice", Map.of());
        assertThat(first).isNotBlank();

        assertThatThrownBy(() -> engine.start("leave", "BK-001", "bob", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BK-001");
    }

    @Test
    @DisplayName("不同 businessKey 互不影响")
    void differentBusinessKeysCoexist() {
        engine.start("leave", "BK-A", "alice", Map.of());
        String b = engine.start("leave", "BK-B", "bob", Map.of());
        assertThat(b).isNotBlank();
    }

    @Test
    @DisplayName("办结后同 businessKey 可重新发起（仅 RUNNING 算冲突）")
    void allowsResubmitAfterCompletion() {
        String first = engine.start("leave", "BK-001", "alice", Map.of());
        finish(first);
        // 第一个已 COMPLETED，同单号重新发起应放行
        String second = engine.start("leave", "BK-001", "alice", Map.of());
        assertThat(second).isNotBlank();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("businessKey 为空 → 不做幂等校验，可无限发起")
    void nullBusinessKeyNoIdempotency() {
        engine.start("leave", "x1", Map.of());
        engine.start("leave", "x2", Map.of());
        assertThat(instRepo.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("并发同 businessKey 双提交：恰好一个成功、其余幂等拒绝（bk 锁消除竞态）")
    void concurrentSameBusinessKey_onlyOneWins() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    engine.start("leave", "BK-RACE", "alice", Map.of());
                    ok.incrementAndGet();
                } catch (IllegalStateException dup) {
                    rejected.incrementAndGet();   // 幂等拒绝
                } catch (Exception e) {
                    rejected.incrementAndGet();
                }
            });
        }
        ready.await();
        go.countDown();          // 齐发，最大化竞态窗口
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(ok.get()).as("恰好一个成功建实例").isEqualTo(1);
        assertThat(rejected.get()).as("其余被幂等拒绝").isEqualTo(n - 1);
        assertThat(instRepo.findByBusinessKey("BK-RACE").stream()
                .filter(i -> i.getStatus() == InstanceStatus.RUNNING).count())
                .as("只剩一个进行中实例，无重复").isEqualTo(1);
    }
}
