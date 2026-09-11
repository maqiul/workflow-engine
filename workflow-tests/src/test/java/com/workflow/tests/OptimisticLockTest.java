package com.workflow.tests;

import com.workflow.concurrency.WorkflowConflictException;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨节点乐观锁（CAS）的跨仓储验证。
 *
 * <p>引擎的并发防护分两层：进程内按流程树根加分段锁（单节点足够），
 * 跨进程则必须靠数据库行的版本号 —— 本用例守的就是后者。
 *
 * <p>为什么"版本号递增"这个看似平凡的断言其实是核心：
 * 它恰恰是 CAS 通路是否<b>真的接通</b>的分水岭。@Version 只是个普通注解，
 * 若 MyBatis 侧忘了注册乐观锁插件、或 JPA 侧的 {@code rebuild} 把版本硬编码成 0、
 * 或迁移脚本漏了 revision 列 —— 上述任何一种，写回都会<b>静默成功</b>，
 * 版本号纹丝不动地停在 1。断言"第二次写入后是 2"能一次性钉死这几种漏法。
 */
@DisplayName("乐观锁 CAS 跨仓储")
class OptimisticLockTest {

    private static JpaPersistence jpa;
    private static MybatisPersistence mb;

    @BeforeAll
    static void startDatabases() {
        jpa = JpaPersistence.getDefault();
        jpa.init();
        mb = MybatisPersistence.getDefault();
        mb.init();
    }

    private record Suite(String label, ProcessRepository procRepo,
                         InstanceRepository instRepo, TaskRepository taskRepo) { }

    /**
     * 内存仓储必须显式打开 CAS：它默认关闭（单 JVM 内并发窗口由 LocalInstanceLocks
     * 兜着，开关是给"用内存实现验证跨节点语义"的场合准备的）。
     */
    private Suite newSuite(String which) {
        ProcessRepository pr;
        InstanceRepository ir;
        TaskRepository tr;
        switch (which) {
            case "JPA" -> {
                jpa.inTransaction(em -> {
                    em.createNativeQuery("DELETE FROM wf_task").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_token").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_instance").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_process_def").executeUpdate();
                    return null;
                });
                pr = jpa.processRepo();
                ir = jpa.instanceRepo();
                tr = jpa.taskRepo();
            }
            case "MyBatis" -> {
                mb.clearTables();
                pr = mb.processRepo();
                ir = mb.instanceRepo();
                tr = mb.taskRepo();
            }
            default -> {
                pr = new InMemoryProcessRepository();
                ir = new InMemoryInstanceRepository(true);
                tr = new InMemoryTaskRepository(true);
            }
        }
        return new Suite(which, pr, ir, tr);
    }

    @Test
    @DisplayName("每次写入都推进版本号 —— 钉死「@Version/插件/迁移列任缺其一」")
    void revisionAdvancesOnEveryWrite() {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            Suite s = newSuite(which);

            // 不走引擎，直接压仓储：本用例要测的是持久层的版本语义，引擎流程另有 300+ 用例守着
            ProcessInstance seed = new ProcessInstance("rev-probe-" + which, 1);
            s.instRepo().save(seed);
            String instId = seed.getId();

            assertThat(s.instRepo().findById(instId).getRevision())
                    .as("%s: 新建实例落库后的初始版本号", which)
                    .isEqualTo(1L);

            // 引擎的写法就是"先重读、再改、再存"，所以这里也照做
            ProcessInstance second = s.instRepo().findById(instId);
            second.setVariable("round", 2);
            s.instRepo().save(second);

            assertThat(s.instRepo().findById(instId).getRevision())
                    .as("%s: 第二次写入后版本号应为 2。若仍停在 1，说明写回没走 CAS ——"
                            + " JPA 漏了 @Version、MyBatis 漏了乐观锁插件，或迁移没加 revision 列", which)
                    .isEqualTo(2L);

            ProcessInstance third = s.instRepo().findById(instId);
            third.setVariable("round", 3);
            s.instRepo().save(third);

            assertThat(s.instRepo().findById(instId).getRevision())
                    .as("%s: 第三次写入后版本号应为 3", which)
                    .isEqualTo(3L);

            // 顺带确认变量真的落盘了：版本号推进与内容写入必须是同一次 UPDATE
            assertThat(s.instRepo().findById(instId).getVariables())
                    .as("%s: 变量应随写入落盘", which)
                    .containsEntry("round", 3);
        }
    }

    /**
     * 两个节点读到同一版本后再各自写入 —— 后提交者必须被拒绝。
     *
     * <p>时序是这里的关键：靠 {@link CyclicBarrier} 把两个线程都卡在"已读完、尚未写"
     * 的位置，冲突才是<b>确定性</b>的，而不是碰运气的。JPA 侧用
     * {@code bindCurrentEm} 把读和写放进同一个事务的同一个 EntityManager ——
     * 否则 {@code save} 内部会重新查库拿到最新版本，条件恒成立、CAS 永不触发，
     * 测试会以"两个都成功"的假绿通过。
     *
     * <p>本用例只对 JPA 断言：内存实现的 CAS 是"检查后写入"两步，不是原子操作，
     * 故意不参与真实并发（它的并发保护在引擎的 LocalInstanceLocks 那一层）。
     */
    @Test
    @DisplayName("同一版本上的两个写入者，恰好一个成功、另一个收到冲突（JPA）")
    void concurrentWritersOnSameRevisionExactlyOneWins() throws Exception {
        Suite s = newSuite("JPA");

        ProcessInstance seed = new ProcessInstance("cas-probe", 1);
        s.instRepo().save(seed);
        String instId = seed.getId();

        assertThat(s.instRepo().findById(instId).getRevision())
                .as("基准版本")
                .isEqualTo(1L);

        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            final int writer = i;
            futures.add(pool.submit(() -> {
                jpa.bindCurrentEm();
                try {
                    // 事务内读 → 实体进入托管态，版本被钉在读取时的值
                    ProcessInstance mine = s.instRepo().findById(instId);
                    bothHaveRead.await(15, TimeUnit.SECONDS);
                    mine.setVariable("writer" + writer, writer);
                    s.instRepo().save(mine);
                    jpa.commitAndUnbind();
                    succeeded.incrementAndGet();
                } catch (WorkflowConflictException e) {
                    jpa.rollbackAndUnbind();
                    conflicted.incrementAndGet();
                } catch (Exception e) {
                    jpa.rollbackAndUnbind();
                    throw new RuntimeException("写入者 " + writer + " 抛出非预期异常", e);
                }
                return null;
            }));
        }

        for (Future<?> f : futures) {
            f.get(45, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(succeeded.get())
                .as("必须恰好一个写入成功（两个都成功 = 丢失更新，正是本功能要防的）")
                .isEqualTo(1);
        assertThat(conflicted.get())
                .as("后提交者必须收到 WorkflowConflictException，引擎据此重读并有限次重试")
                .isEqualTo(1);

        // 胜者写的内容必须完整落盘，且版本只前进一格
        ProcessInstance after = s.instRepo().findById(instId);
        assertThat(after.getRevision()).as("版本只推进一格").isEqualTo(2L);
        assertThat(after.getVariables()).as("胜者的写入必须落盘").hasSize(1);
    }
}
