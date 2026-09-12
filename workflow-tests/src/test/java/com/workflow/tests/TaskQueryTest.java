package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.IWorkflowEngine;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.TaskStatus;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.query.TaskQuery;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskQuery 的四个修复点，在三套仓储上分别成立。
 *
 * <p>之所以坚持跑三遍：查询能力此前只在内存版可用（JPA / MyBatis 的
 * {@code findAll} 落到接口 default 抛异常），单测只覆盖内存版就等于把缺口留在真库上。
 */
@DisplayName("TaskQuery 做实")
class TaskQueryTest {

    private static JpaPersistence jpa;
    private static MybatisPersistence mb;

    @BeforeAll
    static void startDatabases() {
        jpa = JpaPersistence.getDefault();
        jpa.init();
        mb = MybatisPersistence.getDefault();
        mb.init();
    }

    /** 一组仓储 + 装配好的引擎。 */
    private record Harness(WorkflowEngine engine,
                           ProcessRepository procRepo,
                           InstanceRepository instRepo,
                           TaskRepository taskRepo) { }

    private static final String LEAVE = "query-leave";
    private static final String EXPENSE = "query-expense";

    private Harness newHarness(String which) {
        ProcessDefinition leave = ProcessBuilder.create(LEAVE)
                .version(1)
                .start("start")
                .userTask("manager", "经理审批", Candidate.ofAny("u1", "u2"))
                .userTask("hr", "人事确认", Candidate.ofAny("u3"))
                .end("end")
                .connect("start", "manager")
                .connect("manager", "hr")
                .connect("hr", "end")
                .build();
        ProcessDefinition leaveV2 = ProcessBuilder.create(LEAVE)
                .version(2)
                .start("start")
                .userTask("boss", "总监审批", Candidate.ofAny("u9"))
                .end("end")
                .connect("start", "boss")
                .connect("boss", "end")
                .build();
        ProcessDefinition expense = ProcessBuilder.create(EXPENSE)
                .version(1)
                .start("start")
                .userTask("finance", "财务审核", Candidate.ofAny("u5"))
                .end("end")
                .connect("start", "finance")
                .connect("finance", "end")
                .build();

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
                ir = new InMemoryInstanceRepository();
                tr = new InMemoryTaskRepository();
            }
        }
        pr.save(leave);
        pr.save(leaveV2);
        pr.save(expense);
        WorkflowEngine engine = new WorkflowEngine(pr, ir, tr);
        return new Harness(engine, pr, ir, tr);
    }

    /** 三套仓储逐个跑同一个断言。 */
    private void acrossAllRepositories(java.util.function.Consumer<Harness> assertion) {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            assertion.accept(newHarness(which));
        }
    }

    // ---------- 1. 不带 instanceId 也能查到（旧实现永返空列表） ----------

    @Test
    @DisplayName("无 processInstanceId 的全局查询必须返回任务，而不是空列表")
    void globalQueryReturnsTasks() {
        acrossAllRepositories(h -> {
            h.engine().start(LEAVE, Map.of("amount", 500));
            h.engine().start(EXPENSE, Map.of("amount", 9000));

            List<TaskInstance> all = TaskQuery.create()
                    .status(TaskStatus.PENDING)
                    .list(h.engine());

            assertThat(all)
                    .as("两个实例各一个待办")
                    .hasSize(2);
            h.engine().shutdown();
        });
    }

    // ---------- 2/3/4. 三个曾空转的过滤条件 ----------

    @Test
    @DisplayName("processDefinitionKey 过滤真正生效")
    void filterByProcessDefinitionKey() {
        acrossAllRepositories(h -> {
            h.engine().start(LEAVE, Map.of("amount", 500));
            h.engine().start(LEAVE, Map.of("amount", 700));
            h.engine().start(EXPENSE, Map.of("amount", 9000));

            assertThat(TaskQuery.create().processDefinitionKey(LEAVE).list(h.engine()))
                    .as("只应命中 leave 的两个待办")
                    .hasSize(2);
            assertThat(TaskQuery.create().processDefinitionKey(EXPENSE).list(h.engine()))
                    .hasSize(1);
            assertThat(TaskQuery.create().processDefinitionKey("no-such-key").list(h.engine()))
                    .as("不存在的 key 不该命中任何东西")
                    .isEmpty();
            h.engine().shutdown();
        });
    }

    @Test
    @DisplayName("processDefinitionVersion 过滤真正生效")
    void filterByProcessDefinitionVersion() {
        acrossAllRepositories(h -> {
            h.engine().start(LEAVE, 1, Map.of());
            h.engine().start(LEAVE, 2, Map.of());

            assertThat(TaskQuery.create()
                    .processDefinitionKey(LEAVE).processDefinitionVersion(1)
                    .list(h.engine()))
                    .as("v1 的待办在 manager 节点")
                    .allSatisfy(t -> assertThat(t.getNodeId()).isEqualTo("manager"));
            assertThat(TaskQuery.create()
                    .processDefinitionKey(LEAVE).processDefinitionVersion(2)
                    .list(h.engine()))
                    .as("v2 的待办在 boss 节点")
                    .allSatisfy(t -> assertThat(t.getNodeId()).isEqualTo("boss"));
            h.engine().shutdown();
        });
    }

    @Test
    @DisplayName("processVariable 过滤生效，且数值按数值相等比较")
    void filterByProcessVariable() {
        acrossAllRepositories(h -> {
            h.engine().start(LEAVE, Map.of("amount", 500));
            h.engine().start(EXPENSE, Map.of("amount", 9000));

            assertThat(TaskQuery.create().processVariable("amount", 500).list(h.engine()))
                    .as("按 Integer 精确命中")
                    .hasSize(1);
            assertThat(TaskQuery.create().processVariable("amount", 9000L).list(h.engine()))
                    .as("JSON 往返后可能是 Long，须按数值相等命中")
                    .hasSize(1);
            assertThat(TaskQuery.create().processVariable("amount", 123).list(h.engine()))
                    .as("无匹配值不得命中")
                    .isEmpty();
            assertThat(TaskQuery.create()
                    .processVariable("amount", 500).processVariable("missing", "x")
                    .list(h.engine()))
                    .as("多条件取交集")
                    .isEmpty();
            h.engine().shutdown();
        });
    }

    // ---------- 5. 排序真按创建时间 ----------

    @Test
    @DisplayName("orderByCreateTime 按真实创建时间排序，而非 id")
    void orderReallyUsesCreateTime() throws Exception {
        acrossAllRepositories(h -> {
            String early = h.engine().start(EXPENSE, Map.of("amount", 1));
            // 拉开时间差，确保两个任务的 createTime 不在同一毫秒
            sleepSome();
            String late = h.engine().start(LEAVE, Map.of("amount", 2));

            TaskInstance first = h.taskRepo().findByInstanceId(early).get(0);
            TaskInstance second = h.taskRepo().findByInstanceId(late).get(0);
            assertThat(second.getCreateTime())
                    .as("domain 必须携带 create_time，否则排序无从谈起")
                    .isGreaterThan(first.getCreateTime());

            List<TaskInstance> asc = TaskQuery.create()
                    .status(TaskStatus.PENDING).orderByCreateTime().list(h.engine());
            assertThat(asc).extracting(TaskInstance::getId)
                    .containsExactly(first.getId(), second.getId());

            List<TaskInstance> desc = TaskQuery.create()
                    .status(TaskStatus.PENDING).orderByCreateTimeDesc().list(h.engine());
            assertThat(desc).extracting(TaskInstance::getId)
                    .containsExactly(second.getId(), first.getId());
            h.engine().shutdown();
        });
    }

    private static void sleepSome() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 6. count 不再被 limit 截断 ----------

    @Test
    @DisplayName("count() 返回真实命中数，不受 limit 截断")
    void countIsNotTruncatedByLimit() {
        acrossAllRepositories(h -> {
            for (int i = 0; i < 4; i++) {
                h.engine().start(LEAVE, Map.of("amount", i));
            }

            long plain = TaskQuery.create().status(TaskStatus.PENDING).count(h.engine());
            long withLimit = TaskQuery.create().status(TaskStatus.PENDING).limit(2).count(h.engine());
            long listSize = TaskQuery.create().status(TaskStatus.PENDING).limit(2).list(h.engine()).size();

            assertThat(plain).as("4 个实例各一个待办").isEqualTo(4);
            assertThat(withLimit)
                    .as("旧实现会返回 2 —— limit 把计数一起截断了")
                    .isEqualTo(4);
            assertThat(listSize).as("list 仍受 limit 约束").isEqualTo(2);
            h.engine().shutdown();
        });
    }

    // ---------- 7. 候选人过滤仍然有效 ----------

    @Test
    @DisplayName("candidate 过滤与多条件组合")
    void candidateFilterStillWorks() {
        acrossAllRepositories(h -> {
            // 必须显式用 v1：不带版本发起会取最新版（v2 的候选人是 u9，压根没有 u1）
            h.engine().start(LEAVE, 1, Map.of("amount", 1));
            assertThat(TaskQuery.create().candidate("u1").list(h.engine())).hasSize(1);
            assertThat(TaskQuery.create().candidate("nobody").list(h.engine())).isEmpty();
            assertThat(TaskQuery.create().candidate("u1").nodeId("manager").list(h.engine()))
                    .hasSize(1);
            assertThat(TaskQuery.create().candidate("u1").nodeId("hr").list(h.engine()))
                    .as("u1 不是 hr 节点候选人")
                    .isEmpty();
            h.engine().shutdown();
        });
    }
}
