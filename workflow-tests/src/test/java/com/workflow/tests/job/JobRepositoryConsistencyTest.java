package com.workflow.tests.job;

import com.workflow.engine.JobExecutor;
import com.workflow.enums.JobState;
import com.workflow.enums.JobType;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.repository.InMemoryJobRepository;
import com.workflow.repository.JobRepository;
import com.workflow.runtime.Job;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作业仓储跨三套一致（InMemory / JPA / MyBatis）。
 *
 * <p>作业表是跨节点协调的唯一真相源 —— 三套实现必须给出同一答案。
 * 尤其 {@code claim} 的原子性：内存版靠 ConcurrentHashMap.compute，真库版靠带条件 UPDATE，
 * 语义必须等价（只有一个节点领到）。
 */
@DisplayName("作业仓储跨仓储一致")
class JobRepositoryConsistencyTest {

    private static JpaPersistence jpa;
    private static MybatisPersistence mb;

    @BeforeAll
    static void startDatabases() {
        jpa = JpaPersistence.getDefault();
        jpa.init();
        mb = MybatisPersistence.getDefault();
        mb.init();
    }

    private JobRepository repoOf(String which) {
        switch (which) {
            case "JPA" -> {
                jpa.inTransaction(em -> {
                    em.createNativeQuery("DELETE FROM wf_job").executeUpdate();
                    return null;
                });
                return jpa.jobRepo();
            }
            case "MyBatis" -> {
                mb.clearTables();
                return mb.jobRepo();
            }
            default -> {
                return new InMemoryJobRepository();
            }
        }
    }

    private long now() {
        return System.currentTimeMillis();
    }

    @Test
    @DisplayName("三套：保存/到期查询/领取互斥/退避重试/死信/删除 行为一致")
    void allBackendsBehaveIdentically() {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            JobRepository repo = repoOf(which);
            long base = now();

            // 保存
            Job job = new Job("j1", JobType.SERVICE_TASK, "i1", "{\"k\":1}", base - 1, base);
            job.setMaxRetries(1);
            repo.save(job);

            Job loaded = repo.findById("j1");
            assertThat(loaded).as("%s: 能读回", which).isNotNull();
            assertThat(loaded.getType()).as("%s: 类型一致", which).isEqualTo(JobType.SERVICE_TASK);
            assertThat(loaded.getInstanceId()).isEqualTo("i1");
            assertThat(loaded.getPayload()).isEqualTo("{\"k\":1}");
            assertThat(loaded.getDueAt()).as("%s: 到期时刻一致", which).isEqualTo(base - 1);
            assertThat(loaded.getState()).isEqualTo(JobState.READY);

            // 未到期的不出现在 findDue
            Job future = new Job("j2", JobType.SERVICE_TASK, "i1", null, base + 60_000, base);
            repo.save(future);
            assertThat(repo.findDue(base, 10)).as("%s: 只取到期作业", which)
                    .extracting(Job::getId).containsExactly("j1");

            // 领取：第一个成功，第二个失败（互斥）
            assertThat(repo.claim("j1", "node-A", base, base + 60_000))
                    .as("%s: 首次领取成功", which).isTrue();
            assertThat(repo.claim("j1", "node-B", base, base + 60_000))
                    .as("%s: 已被领取则失败", which).isFalse();
            assertThat(repo.findById("j1").getLockOwner()).isEqualTo("node-A");

            // 退避重试：清锁 + 计数 + 报错
            repo.markRetry("j1", "第一次失败", base + 5000);
            Job retried = repo.findById("j1");
            assertThat(retried.getState()).as("%s: 退避后回到 READY", which).isEqualTo(JobState.READY);
            assertThat(retried.getRetries()).as("%s: 重试计数 +1", which).isEqualTo(1);
            assertThat(retried.getLastError()).isEqualTo("第一次失败");
            assertThat(retried.getDueAt()).as("%s: 重排期生效", which).isEqualTo(base + 5000);
            assertThat(retried.getLockOwner()).as("%s: 退避释放锁", which).isNull();
            assertThat(retried.getLockExpiresAt()).isZero();

            // 死信
            repo.markFailed("j1", "彻底失败");
            Job dead = repo.findById("j1");
            assertThat(dead.getState()).as("%s: 进死信", which).isEqualTo(JobState.FAILED);
            assertThat(dead.getRetries()).isEqualTo(2);
            assertThat(dead.getLastError()).isEqualTo("彻底失败");
            assertThat(repo.countByState(JobState.FAILED)).as("%s: 死信计数", which).isEqualTo(1);
            assertThat(repo.findByState(JobState.FAILED, 10)).as("%s: 死信可查", which)
                    .extracting(Job::getId).containsExactly("j1");

            // 按实例查 + 按实例删
            assertThat(repo.findByInstanceId("i1")).as("%s: 按实例查", which)
                    .extracting(Job::getId).contains("j1", "j2");
            assertThat(repo.deleteByInstanceId("i1")).as("%s: 按实例删", which).isEqualTo(2);
            assertThat(repo.findById("j1")).isNull();
            assertThat(repo.findById("j2")).isNull();
        }
    }

    @Test
    @DisplayName("三套：同一作业并发领取只有一个成功（真库靠条件 UPDATE）")
    void concurrentClaimOnlyOneWins() throws Exception {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            JobRepository repo = repoOf(which);
            long base = now();
            repo.save(new Job("j1", JobType.SERVICE_TASK, "i1", null, base - 1, base));

            int threads = 6;
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (repo.claim("j1", "node-" + idx, base, base + 60_000)) {
                        winners.incrementAndGet();
                    }
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            assertThat(winners.get()).as("%s: 只有一个节点领到", which).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("三套：租约过期后可被接管")
    void leaseExpiryAllowsTakeover() {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            JobRepository repo = repoOf(which);
            long base = now();
            repo.save(new Job("j1", JobType.SERVICE_TASK, "i1", null, base - 1, base));

            assertThat(repo.claim("j1", "node-A", base, base + 1_000)).isTrue();
            assertThat(repo.claim("j1", "node-B", base, base + 60_000))
                    .as("%s: 租约未过期抢不走", which).isFalse();
            assertThat(repo.claim("j1", "node-B", base + 2_000, base + 62_000))
                    .as("%s: 租约过期可接管", which).isTrue();
            assertThat(repo.findById("j1").getLockOwner()).isEqualTo("node-B");
        }
    }
}
