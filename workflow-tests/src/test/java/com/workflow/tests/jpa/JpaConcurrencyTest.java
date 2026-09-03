package com.workflow.tests.jpa;

import com.workflow.enums.TaskStatus;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 路径的并发正确性 —— 探针性质。
 *
 * <p>引擎的分段锁只在<b>单个 JVM 内</b>有效；一旦落到真实数据库，
 * 并发正确性必须由行级乐观锁（revision）兜底。本用例检验当前 JPA 映射
 * 是否具备这个能力：两名审批人同时通过同一 ALL 会签任务。
 *
 * <p>判定标准：
 * <ul>
 *   <li>任务最终 COMPLETED 且 {@code completedApprovers} 恰为 2 人；</li>
 *   <li>下游 hr 恰好一个待办（Token 不被重复推进）；</li>
 *   <li>不允许出现内部一致性异常（迟到者被前置校验正当拒绝属预期行为）。</li>
 * </ul>
 */
@DisplayName("JPA 会签并发")
class JpaConcurrencyTest extends JpaEngineTestBase {

    private static final int ROUNDS = 15;
    private static final long AWAIT_SECONDS = 15;

    private ExecutorService pool;

    @BeforeEach
    void startPool() {
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("两名审批人同时通过同一会签任务，数据库侧不得丢失更新或重复推进")
    void concurrentAllSignOnJpa() throws Exception {
        register(simple("jpa-cc")
                .start("start")
                .userTask("review", "会签", all("u1", "u2"))
                .userTask("hr", "人事确认", any("u3"))
                .end("end")
                .connect("start", "review")
                .connect("review", "hr")
                .connect("hr", "end")
                .build());

        CopyOnWriteArrayList<Throwable> fatal = new CopyOnWriteArrayList<>();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger approvers2 = new AtomicInteger();
        AtomicInteger hrExactlyOne = new AtomicInteger();

        for (int round = 0; round < ROUNDS; round++) {
            String instanceId = engine.start("jpa-cc", Map.of());
            String taskId = taskRepo.findByInstanceId(instanceId).stream()
                    .filter(t -> "review".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                    .findFirst().orElseThrow().getId();

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch fire = new CountDownLatch(1);

            java.util.function.Function<String, Runnable> action = user -> () -> {
                ready.countDown();
                try {
                    fire.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                    engine.completeTask(taskId, user, true);
                } catch (Throwable t) {
                    // 迟到者被前置校验拒绝属正当行为，其余一律视为缺陷
                    if (!(t instanceof IllegalStateException
                            && String.valueOf(t.getMessage()).startsWith("任务非 PENDING 状态"))) {
                        fatal.add(t);
                    }
                }
            };

            var f1 = pool.submit(action.apply("u1"));
            var f2 = pool.submit(action.apply("u2"));
            assertThat(ready.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            fire.countDown();
            f1.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            f2.get(AWAIT_SECONDS, TimeUnit.SECONDS);

            TaskInstance task = taskRepo.findById(taskId);
            if (task.getStatus() == TaskStatus.COMPLETED) completed.incrementAndGet();
            if (task.getCompletedApprovers().size() == 2) approvers2.incrementAndGet();
            long pendingHr = taskRepo.findByInstanceId(instanceId).stream()
                    .filter(t -> "hr".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                    .count();
            if (pendingHr == 1) hrExactlyOne.incrementAndGet();
        }

        StringBuilder sb = new StringBuilder();
        if (!fatal.isEmpty()) {
            sb.append(String.format("%n内部异常 %d 个，首个: %s", fatal.size(), fatal.get(0)));
        }
        if (completed.get() != ROUNDS) sb.append(String.format("%n任务 COMPLETED 仅 %d/%d 轮", completed.get(), ROUNDS));
        if (approvers2.get() != ROUNDS) sb.append(String.format("%ncompletedApprovers=2 仅 %d/%d 轮（数据库侧丢失更新）", approvers2.get(), ROUNDS));
        if (hrExactlyOne.get() != ROUNDS) sb.append(String.format("%n下游 hr 恰 1 待办仅 %d/%d 轮（Token 重复推进）", hrExactlyOne.get(), ROUNDS));

        assertThat(sb.toString()).as("JPA 并发正确性（%d 轮）", ROUNDS).isEmpty();
    }
}
