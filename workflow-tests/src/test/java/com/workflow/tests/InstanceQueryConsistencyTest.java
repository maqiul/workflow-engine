package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实例列表查询的跨仓储一致性 —— InMemory / JPA / MyBatis 必须给出同一答案。
 *
 * <p>动机：{@code findByProcessKey} / {@code findAll} / {@code findByStatus} /
 * {@code findByProcessKeyAndVersion} 在接口上带 {@code default} 实现（抛
 * {@code UnsupportedOperationException}）。内存版全都有，JPA 与 MyBatis 一个都没有 ——
 * 于是「历史查询」这套东西在真实数据库上一调用就炸，而内存测试全绿。
 *
 * <p>带 {@code default} 抛异常的接口是危险设计：缺失实现不会在编译期暴露。
 * 本用例就是把这类缺口钉在运行期的最低成本手段。
 */
@DisplayName("实例查询跨仓储一致性")
class InstanceQueryConsistencyTest {

    private record Bundle(String label,
                          ProcessRepository procRepo,
                          InstanceRepository instRepo,
                          TaskRepository taskRepo) { }

    private static JpaPersistence jpa;
    private static MybatisPersistence mb;

    @BeforeAll
    static void startRealDatabases() {
        jpa = JpaPersistence.getDefault();
        jpa.init();
        mb = MybatisPersistence.getDefault();
        mb.init();
    }

    @AfterAll
    static void stopRealDatabases() {
        if (jpa != null) jpa.close();
        if (mb != null) mb.close();
    }

    /**
     * 在同一实例上发起并推进出「一个已完成 + 一个运行中」的样本数据。
     */
    private List<ProcessInstance> seedAndQuery(Bundle b) {
        ProcessDefinition v1 = ProcessBuilder.create("consistency")
                .version(1)
                .start("start")
                .userTask("review", "审批", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();
        ProcessDefinition v2 = ProcessBuilder.create("consistency")
                .version(2)
                .start("start")
                .userTask("review", "审批", Candidate.ofAny("u2"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v2.getVersion()).isEqualTo(2);
        // 两个版本都要落库，否则按版本发起查不到定义
        b.procRepo().save(v1);
        b.procRepo().save(v2);

        WorkflowEngine engine = new WorkflowEngine(b.procRepo(), b.instRepo(), b.taskRepo());
        String doneId = engine.start("consistency", v1.getVersion(), Map.of());
        engine.completeTask(engine.getInstance(doneId).getTasks().get(0).getId(), "u1", true);
        String runningId = engine.start("consistency", v2.getVersion(), Map.of());
        engine.shutdown();

        assertThat(doneId).isNotEqualTo(runningId);
        return b.instRepo().findAll();
    }

    @Test
    @DisplayName("三套仓储的 findAll / findByProcessKey / findByStatus / 按版本查询结果一致")
    void allThreeRepositoriesAnswerTheSame() {
        // 每个仓储各跑一遍：表数据互不共享，因此各自都该得到「2 个实例」
        Bundle inMemory = new Bundle("InMemory",
                new com.workflow.repository.InMemoryProcessRepository(),
                new com.workflow.repository.InMemoryInstanceRepository(),
                new com.workflow.repository.InMemoryTaskRepository());
        Bundle jpaBundle = new Bundle("JPA",
                jpa.processRepo(), jpa.instanceRepo(), jpa.taskRepo());
        Bundle mbBundle = new Bundle("MyBatis",
                mb.processRepo(), mb.instanceRepo(), mb.taskRepo());

        // JPA / MyBatis 复用同一个内存库，先清干净避免历史残留干扰计数
        jpa.inTransaction(em -> {
            em.createNativeQuery("DELETE FROM wf_task").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_token").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_instance").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_process_def").executeUpdate();
            return null;
        });
        mb.clearTables();

        for (Bundle b : List.of(inMemory, jpaBundle, mbBundle)) {
            List<ProcessInstance> all = seedAndQuery(b);

            assertThat(all)
                    .as("%s: findAll 应返回 2 个实例", b.label())
                    .hasSize(2);

            assertThat(b.instRepo().findByProcessKey("consistency"))
                    .as("%s: findByProcessKey", b.label())
                    .hasSize(2);

            assertThat(b.instRepo().findByStatus(InstanceStatus.COMPLETED))
                    .as("%s: findByStatus(COMPLETED)", b.label())
                    .filteredOn(i -> i.getProcessKey().equals("consistency"))
                    .hasSize(1);

            int v1 = all.stream().mapToInt(ProcessInstance::getProcessVersion).min().orElseThrow();
            assertThat(b.instRepo().findByProcessKeyAndVersion("consistency", v1))
                    .as("%s: findByProcessKeyAndVersion(v%d)", b.label(), v1)
                    .hasSize(1);

            // 变量必须完整读回，否则查询条件是空转的
            Map<String, Object> vars = all.get(0).getVariables();
            assertThat(vars)
                    .as("%s: 实例变量应可读回", b.label())
                    .isNotNull();
        }
    }
}
