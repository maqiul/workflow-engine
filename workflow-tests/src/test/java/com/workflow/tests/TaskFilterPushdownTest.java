package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.TaskStatus;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.query.TaskQuery;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskFilter;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务查询条件下推 —— 盯的是「下推之后结果一模一样」，不是「变快了」。
 *
 * <p>下推是纯性能改动，正确性才是它的风险所在：条件从内存搬到 SQL 之后，
 * 表达力一旦不等价，就会出现「查不到」「查到不该有的」「翻页漏数据」这三类静默错误。
 * 所以这里三套仓储（内存 / JPA / MyBatis）跑同一份断言，再单独盯住
 * WHERE 下推到 SQL 之后最容易出错的三处：
 *
 * <ol>
 *   <li>候选人 LIKE 粗筛的<b>边界</b>：{@code u1} 不能命中 {@code u10}；</li>
 *   <li>粗筛的<b>假阳性</b>：候选组名与用户 id 同名时（都属于 {@code u1}），
 *       SQL 分不出这两条，必须由内存精筛兜住；</li>
 *   <li>粗筛场景下的<b>分页位置</b>：limit 只能在精筛之后生效，否则假阳性会把真匹配挤空。</li>
 * </ol>
 */
@DisplayName("任务查询条件下推")
class TaskFilterPushdownTest {

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

    private static final String MIX = "pushdown-mix";

    private Harness newHarness(String which) {
        ProcessRepository pr;
        InstanceRepository ir;
        TaskRepository tr;
        switch (which) {
            case "JPA" -> {
                jpa.inTransaction(em -> {
                    em.createNativeQuery("DELETE FROM wf_task").executeUpdate();
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
        return new Harness(new WorkflowEngine(pr, ir, tr), pr, ir, tr);
    }

    /** 三套仓储逐个跑同一个断言。 */
    private void acrossAllRepositories(Consumer<Harness> assertion) {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            assertion.accept(newHarness(which));
        }
    }

    /**
     * 直接落一条待办任务，<b>不走引擎</b>。
     *
     * <p>候选组、创建时间这些正是要精确控制的东西，走引擎反而控制不了 ——
     * 引擎建任务时会展开候选组，而"组名与用户名同名"这个假阳性前提要求
     * {@code groupIds} 原样保留。
     */
    private static void saveTask(Harness h, String id, Candidate candidate, long createTime) {
        h.taskRepo().save(TaskInstance.reconstruct(
                id, "inst-" + id, "tok-" + id, "node-" + id,
                candidate, Set.of(), TaskStatus.PENDING, 0L, createTime));
    }

    // ---------- 1. 粗筛的边界 ----------

    @Test
    @DisplayName("候选人粗筛不会把 u10 当成 u1")
    void candidateFilterIsExactOnUserIdBoundary() {
        acrossAllRepositories(h -> {
            saveTask(h, "t-u1", Candidate.ofAny("u1"), 1000L);
            saveTask(h, "t-u10", Candidate.ofAny("u10"), 2000L);
            saveTask(h, "t-u2", Candidate.ofAny("u2"), 3000L);

            assertThat(h.taskRepo().findPaged(TaskFilter.create().candidateUser("u1")))
                    .as("u10 含有子串 u1，但 JSON 里的值自带引号，粗筛就不该命中它")
                    .extracting(TaskInstance::getId)
                    .containsExactly("t-u1");
        });
    }

    // ---------- 2. 粗筛的假阳性必须被精筛兜住 ----------

    @Test
    @DisplayName("候选组名与用户 id 同名时，组任务不算个人待办")
    void candidateGroupSharingUserIdIsRefinedOut() {
        acrossAllRepositories(h -> {
            // 两条任务的 candidate_json 里都含 "u1"，SQL 的 LIKE 分不出区别
            saveTask(h, "t-group", Candidate.ofGroups(Set.of("u1"), CandidateStrategy.ANY), 1000L);
            saveTask(h, "t-user", Candidate.ofAny("u1"), 2000L);

            assertThat(h.taskRepo().findPaged(TaskFilter.create().candidateUser("u1")))
                    .as("粗筛命中两条，精筛必须只留个人待办那条")
                    .extracting(TaskInstance::getId)
                    .containsExactly("t-user");

            assertThat(h.taskRepo().findPaged(TaskFilter.create().candidateGroup("u1")))
                    .as("反过来查组也必须只留组任务，两条路不能串味")
                    .extracting(TaskInstance::getId)
                    .containsExactly("t-group");
        });
    }

    // ---------- 3. 粗筛场景下分页的位置（核心） ----------

    @Test
    @DisplayName("走粗筛时 limit 不下推：假阳性不会把第一页挤空")
    void pagingHappensAfterRefinementWhenCoarseFilterIsUsed() {
        acrossAllRepositories(h -> {
            // 前 5 条是假阳性（候选组名 = u1），真待办的创建时间更晚、排在后面。
            // 若把 limit 下推到 SQL，SQL 会先截出 5 条全是假阳性的行，
            // 精筛之后结果为空 —— 这正是不能下推 limit 的原因
            for (int i = 0; i < 5; i++) {
                saveTask(h, "fake-" + i,
                        Candidate.ofGroups(Set.of("u1"), CandidateStrategy.ANY), 1000L + i);
            }
            for (int i = 0; i < 5; i++) {
                saveTask(h, "real-" + i, Candidate.ofAny("u1"), 2000L + i);
            }

            assertThat(h.taskRepo().findPaged(TaskFilter.create()
                    .candidateUser("u1").orderByCreateTimeAsc().limit(5).offset(0)))
                    .as("第一页必须是 5 条真待办")
                    .extracting(TaskInstance::getId)
                    .containsExactly("real-0", "real-1", "real-2", "real-3", "real-4");

            assertThat(h.taskRepo().findPaged(TaskFilter.create()
                    .candidateUser("u1").orderByCreateTimeAsc().limit(2).offset(4)))
                    .as("翻到第三页只剩余下那条 —— 分页基数要用精筛后的真命中数")
                    .extracting(TaskInstance::getId)
                    .containsExactly("real-4");

            assertThat(h.taskRepo().countByFilter(TaskFilter.create().candidateUser("u1")))
                    .as("总数只数真待办")
                    .isEqualTo(5);
        });
    }

    // ---------- 4. 无粗筛条件时 limit 确实下推，且翻页正确 ----------

    @Test
    @DisplayName("limit 可下推时翻页正确，count 不受分页影响")
    void pagingIsCorrectWhenLimitCanBePushedDown() {
        acrossAllRepositories(h -> {
            for (int i = 0; i < 7; i++) {
                saveTask(h, "t" + i, Candidate.ofAny("u1"), 1000L + i);
            }

            assertThat(h.taskRepo().findPaged(TaskFilter.create()
                    .status(TaskStatus.PENDING).orderByCreateTimeAsc().limit(3).offset(0)))
                    .extracting(TaskInstance::getId)
                    .containsExactly("t0", "t1", "t2");

            assertThat(h.taskRepo().findPaged(TaskFilter.create()
                    .status(TaskStatus.PENDING).orderByCreateTimeAsc().limit(3).offset(3)))
                    .extracting(TaskInstance::getId)
                    .containsExactly("t3", "t4", "t5");

            assertThat(h.taskRepo().findPaged(TaskFilter.create()
                    .status(TaskStatus.PENDING).orderByCreateTimeDesc().limit(2)))
                    .as("降序取前两条")
                    .extracting(TaskInstance::getId)
                    .containsExactly("t6", "t5");

            assertThat(h.taskRepo().countByFilter(TaskFilter.create().status(TaskStatus.PENDING)))
                    .as("总数必须绕过分页 —— 分页组件靠它算总页数")
                    .isEqualTo(7);
        });
    }

    // ---------- 5. 可下推与不可下推的条件混用 ----------

    @Test
    @DisplayName("候选人（可下推）与流程变量（不可下推）混用时结果正确")
    void mixedConditionsProduceCorrectResult() {
        acrossAllRepositories(h -> {
            h.procRepo().save(ProcessBuilder.create(MIX)
                    .version(1)
                    .start("start")
                    .userTask("apply", "申请", Candidate.ofAny("u1"))
                    .end("end")
                    .connect("start", "apply")
                    .connect("apply", "end")
                    .build());

            h.engine().start(MIX, Map.of("dept", "A"));
            h.engine().start(MIX, Map.of("dept", "B"));
            h.engine().start(MIX, Map.of("dept", "A"));

            assertThat(TaskQuery.create()
                    .candidate("u1")
                    .processVariable("dept", "A")
                    .list(h.engine()))
                    .as("变量条件下推不了，但候选人条件已经把候选集缩小，内存补过滤后仍应正确")
                    .hasSize(2);

            assertThat(TaskQuery.create()
                    .candidate("u1")
                    .processVariable("dept", "A")
                    .count(h.engine()))
                    .as("count 走同一条过滤链但不经分页")
                    .isEqualTo(2);

            assertThat(TaskQuery.create()
                    .candidate("u1")
                    .processVariable("dept", "A")
                    .limit(1)
                    .list(h.engine()))
                    .as("混用场景下分页在内存里做，不该把第一条漏掉")
                    .hasSize(1);

            h.engine().shutdown();
        });
    }
}
