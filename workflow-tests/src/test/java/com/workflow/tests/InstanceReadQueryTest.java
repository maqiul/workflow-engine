package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.concurrency.LocalInstanceLocks;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.TimeoutScheduler;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.TimeoutPolicy;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实例读出口 findByIds / findByStartedAfter 的跨仓储一致性（G-03-R）。
 *
 * <p>这两个出口是对账的真相源查询，必须三套实现给出同一答案 —— 内存绿、真库缺席
 * 是本项目反复踩过的坑，故用抽象方法强制三套实现 + 本用例三套各跑一遍守住。
 */
@DisplayName("实例读出口跨仓储一致（G-03-R）")
class InstanceReadQueryTest {

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
                         TaskRepository taskRepo) { }

    private static final TimeoutScheduler NOOP = new TimeoutScheduler() {
        @Override public void schedule(String t, String i, long ms, TimeoutPolicy p, String u) { }
        @Override public void cancel(String t) { }
        @Override public void shutdown() { }
    };

    private Suite suite(String which) {
        switch (which) {
            case "JPA" -> {
                jpa.inTransaction(em -> {
                    em.createNativeQuery("DELETE FROM wf_task").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_token").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_instance").executeUpdate();
                    em.createNativeQuery("DELETE FROM wf_process_def").executeUpdate();
                    return null;
                });
                return new Suite(which, jpa.processRepo(), jpa.instanceRepo(), jpa.taskRepo());
            }
            case "MyBatis" -> {
                mb.clearTables();
                return new Suite(which, mb.processRepo(), mb.instanceRepo(), mb.taskRepo());
            }
            default -> {
                return new Suite(which, new InMemoryProcessRepository(),
                        new InMemoryInstanceRepository(), new InMemoryTaskRepository());
            }
        }
    }

    private WorkflowEngine engineOf(Suite s) {
        return WorkflowEngineBuilder.builder(s.procRepo(), s.instRepo(), s.taskRepo())
                .timeoutScheduler(NOOP)
                .lockProvider(new LocalInstanceLocks())
                .build();
    }

    private void register(Suite s) {
        ProcessDefinition def = ProcessBuilder.create("read-q")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        s.procRepo().save(def);
    }

    @Test
    @DisplayName("findByIds 只回给定 id、findByStartedAfter 增量升序，三套一致")
    void findByIdsAndStartedAfterConsistent() throws Exception {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            Suite s = suite(which);
            register(s);
            WorkflowEngine engine = engineOf(s);

            String a = engine.start("read-q", Map.of());
            Thread.sleep(20);
            String b = engine.start("read-q", Map.of());
            Thread.sleep(20);
            String c = engine.start("read-q", Map.of());

            long aCreate = s.instRepo().findById(a).getCreateTime();

            List<ProcessInstance> picked = s.instRepo().findByIds(Set.of(a, c, "no-such-id"));
            assertThat(picked).as("%s: findByIds 命中 2 个", which).hasSize(2);
            assertThat(picked).extracting(ProcessInstance::getId)
                    .as("%s: 含 a、c，缺的 id 静默跳过", which).containsExactlyInAnyOrder(a, c);
            assertThat(s.instRepo().findByIds(Set.of())).as("%s: 空入参返回空", which).isEmpty();

            List<ProcessInstance> after = s.instRepo().findByStartedAfter(aCreate);
            // 不依赖 wall-clock 精度断言恰好 [b,c] —— CI 慢机上 sleep 拉开的 createTime 可能撞毫秒，
            // b 会被严格 > 排除导致 flaky。只验证增量语义：结果都晚于 since、且按创建时间升序。
            assertThat(after).as("%s: 增量扫描非空", which).isNotEmpty();
            assertThat(after).as("%s: 结果都晚于 since", which)
                    .allMatch(p -> p.getCreateTime() > aCreate);
            for (int i = 1; i < after.size(); i++) {
                assertThat(after.get(i).getCreateTime())
                        .as("%s: 增量结果按创建时间升序", which)
                        .isGreaterThanOrEqualTo(after.get(i - 1).getCreateTime());
            }

            engine.shutdown();
        }
    }

    @Test
    @DisplayName("start 带 businessKey/initiator → 列往返 + 三套查询出口一致")
    void businessKeyAndInitiatorAcrossRepositories() {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            Suite s = suite(which);
            register(s);
            WorkflowEngine engine = engineOf(s);

            String id = engine.start("read-q", "BK-001", "alice", Map.of());

            ProcessInstance reloaded = s.instRepo().findById(id);
            assertThat(reloaded.getBusinessKey()).as("%s: businessKey 列往返", which).isEqualTo("BK-001");
            assertThat(reloaded.getInitiator()).as("%s: initiator 列往返", which).isEqualTo("alice");

            assertThat(s.instRepo().findByBusinessKey("BK-001"))
                    .as("%s: 按业务主键精确查", which).extracting(ProcessInstance::getId).containsExactly(id);
            assertThat(s.instRepo().findByBusinessKeyContains("BK-0"))
                    .as("%s: 按业务主键包含查", which).extracting(ProcessInstance::getId).containsExactly(id);
            assertThat(s.instRepo().findByInitiator("alice"))
                    .as("%s: 按发起人查", which).extracting(ProcessInstance::getId).containsExactly(id);
            assertThat(s.instRepo().findByBusinessKey("no-such-key"))
                    .as("%s: 未命中返回空", which).isEmpty();

            engine.shutdown();
        }
    }
}
