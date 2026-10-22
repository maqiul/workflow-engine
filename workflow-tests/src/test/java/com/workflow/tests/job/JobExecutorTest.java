package com.workflow.tests.job;

import com.workflow.engine.JobExecutor;
import com.workflow.enums.JobState;
import com.workflow.enums.JobType;
import com.workflow.repository.InMemoryJobRepository;
import com.workflow.repository.JobRepository;
import com.workflow.runtime.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步作业执行器行为契约。
 *
 * <p>钉死六件事：正常执行后删除、失败退避重试、重试耗尽进死信、
 * 领取互斥（多节点只执行一次）、租约过期可被接管、作业落库跨"重启"仍在。
 */
@DisplayName("异步作业执行器")
class JobExecutorTest {

    private JobRepository repo;
    private JobExecutor executor;

    @BeforeEach
    void setUp() {
        repo = new InMemoryJobRepository();
        executor = new JobExecutor(repo)
                .nodeId("node-A")
                .retryBackoffBaseMillis(10)
                .retryBackoffMaxMillis(50);
    }

    private long now() {
        return System.currentTimeMillis();
    }

    @Test
    @DisplayName("到期作业被执行后删除（终态不留行）")
    void executesAndDeletes() {
        AtomicInteger ran = new AtomicInteger();
        executor.register(JobType.SERVICE_TASK, job -> ran.incrementAndGet());
        executor.submit("j1", JobType.SERVICE_TASK, "i1", "{}", now() - 1);

        assertThat(executor.executeIfClaimed("j1")).isTrue();

        assertThat(ran.get()).isEqualTo(1);
        assertThat(repo.findById("j1")).as("成功后删除作业").isNull();
        assertThat(executor.executedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("未到期的作业不被执行")
    void doesNotExecuteBeforeDue() {
        AtomicInteger ran = new AtomicInteger();
        executor.register(JobType.SERVICE_TASK, job -> ran.incrementAndGet());
        executor.submit("j1", JobType.SERVICE_TASK, "i1", "{}", now() + 60_000);

        assertThat(executor.executeIfClaimed("j1")).isFalse();
        assertThat(ran.get()).isZero();
        assertThat(repo.findById("j1")).as("未到期作业仍在").isNotNull();
    }

    @Test
    @DisplayName("执行失败 → 退避重试；重试耗尽 → 死信（行保留可查）")
    void retriesThenDeadLetter() {
        AtomicInteger attempts = new AtomicInteger();
        executor.register(JobType.SERVICE_TASK, job -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("外部依赖不可用");
        });
        long base = now();
        executor.submit("j1", JobType.SERVICE_TASK, "i1", "{}", base - 1, 2);   // 最多重试 2 次

        // 第一次：失败 → 退避重排
        executor.executeIfClaimed("j1");
        Job afterFirst = repo.findById("j1");
        assertThat(afterFirst).isNotNull();
        assertThat(afterFirst.getState()).isEqualTo(JobState.READY);
        assertThat(afterFirst.getRetries()).isEqualTo(1);
        assertThat(afterFirst.getLastError()).contains("外部依赖不可用");
        // 退避把到期时刻推到「基线 + 退避时长」之后（锚定基线，不用 now()，避免毫秒漂移）
        assertThat(afterFirst.getDueAt()).as("退避后到期时刻被推后")
                .isGreaterThanOrEqualTo(base + 10);
        assertThat(afterFirst.getLockOwner()).as("退避时释放锁").isNull();

        // 第二次：再失败
        afterFirst.reschedule(base - 1);   // 拉回到期，模拟退避已过
        repo.save(afterFirst);
        executor.executeIfClaimed("j1");
        Job afterSecond = repo.findById("j1");
        assertThat(afterSecond.getRetries()).isEqualTo(2);

        // 第三次：重试耗尽 → 死信
        afterSecond.reschedule(base - 1);
        repo.save(afterSecond);
        executor.executeIfClaimed("j1");

        Job dead = repo.findById("j1");
        assertThat(dead).as("死信保留行，不删除").isNotNull();
        assertThat(dead.getState()).isEqualTo(JobState.FAILED);
        assertThat(dead.getLastError()).contains("外部依赖不可用");
        assertThat(repo.countByState(JobState.FAILED)).isEqualTo(1);
        assertThat(executor.deadLetters(10)).hasSize(1);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("多节点并发领取：同一作业只被一个节点执行")
    void claimIsExclusiveAcrossNodes() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        int n = 8;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Boolean> claimed = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(n);

        // 先落一条到期作业（8 个节点共享同一仓储 = 模拟多节点共库）
        executor.submit("j1", JobType.SERVICE_TASK, "i1", "{}", now() - 1);

        // 8 个节点各自的执行器共享同一个仓储（模拟多节点共库）
        for (int i = 0; i < n; i++) {
            final int idx = i;
            pool.submit(() -> {
                JobExecutor node = new JobExecutor(repo).nodeId("node-" + idx);
                node.register(JobType.SERVICE_TASK, job -> {
                    ran.incrementAndGet();
                    try {
                        Thread.sleep(50);   // 拉长执行窗口，放大竞争
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                });
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                synchronized (claimed) {
                    claimed.add(node.executeIfClaimed("j1"));
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(ran.get()).as("只应真正执行一次").isEqualTo(1);
        assertThat(claimed.stream().filter(Boolean::booleanValue).count())
                .as("只有一个节点领取成功").isEqualTo(1);
    }

    @Test
    @DisplayName("租约过期后作业可被别的节点接管（节点崩溃不丢作业）")
    void leaseExpiryAllowsTakeover() {
        AtomicInteger ran = new AtomicInteger();
        executor.register(JobType.SERVICE_TASK, job -> ran.incrementAndGet());
        executor.submit("j1", JobType.SERVICE_TASK, "i1", "{}", now() - 1);

        // 节点 A 领取后「崩溃」：只领取不执行
        assertThat(repo.claim("j1", "node-A", now(), now() + 1_000)).isTrue();
        assertThat(repo.findById("j1").getLockOwner()).isEqualTo("node-A");

        // 租约未过期时别人抢不走
        assertThat(repo.claim("j1", "node-B", now(), now() + 60_000)).isFalse();

        // 租约过期后可以接管
        long afterLease = now() + 2_000;
        assertThat(repo.claim("j1", "node-B", afterLease, afterLease + 60_000)).isTrue();
        assertThat(repo.findById("j1").getLockOwner()).isEqualTo("node-B");
    }

    @Test
    @DisplayName("作业落库 → 换执行器（模拟进程重启）后仍能执行")
    void jobSurvivesRestart() {
        AtomicInteger ran = new AtomicInteger();
        executor.register(JobType.SERVICE_TASK, job -> ran.incrementAndGet());
        executor.submit("j1", JobType.SERVICE_TASK, "i1", "{}", now() - 1);

        // 模拟重启：新建执行器（仓储不变 —— 作业在库里）
        JobExecutor afterRestart = new JobExecutor(repo).nodeId("node-B");
        AtomicInteger ran2 = new AtomicInteger();
        afterRestart.register(JobType.SERVICE_TASK, job -> ran2.incrementAndGet());
        afterRestart.pollOnce();

        assertThat(ran2.get()).as("重启后作业仍被执行").isEqualTo(1);
        assertThat(repo.findById("j1")).isNull();
    }

    @Test
    @DisplayName("无处理器的作业直接进死信（不无限重试）")
    void unregisteredTypeGoesStraightToDeadLetter() {
        executor.submit("j1", JobType.SERVICE_TASK, "i1", "{}", now() - 1);
        // 不注册处理器
        executor.executeIfClaimed("j1");

        Job dead = repo.findById("j1");
        assertThat(dead).isNotNull();
        assertThat(dead.getState()).isEqualTo(JobState.FAILED);
        assertThat(dead.getLastError()).contains("没有为作业类型注册处理器");
    }

    @Test
    @DisplayName("按 id 提交幂等：重复提交覆盖同一行，不产生重复作业")
    void submitIsIdempotentById() {
        long base = now();
        executor.submit("j1", JobType.SERVICE_TASK, "i1", "{}", base + 1000);
        executor.submit("j1", JobType.SERVICE_TASK, "i2", "{}", base + 2000);

        assertThat(repo.findByInstanceId("i1")).isEmpty();
        assertThat(repo.findByInstanceId("i2")).hasSize(1);
        assertThat(repo.findById("j1").getDueAt()).isEqualTo(base + 2000);
    }
}
