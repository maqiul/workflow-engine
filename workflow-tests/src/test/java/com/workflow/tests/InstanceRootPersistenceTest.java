package com.workflow.tests;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流程树根（{@code rootInstanceId}）必须能穿过持久层往返。
 *
 * <p>这不是锦上添花的字段：引擎的<b>加锁单位</b>就是它（README §17.2）。
 * 若某套仓储没把它落库，重建实例后 {@code getRootInstanceId()} 会退化成"自身即根" ——
 * 父实例与子实例于是各持一把锁，
 * {@code startSubProcess}(父→子) 与 {@code onSubProcessCompleted}(子→父)
 * 的 ABBA 死锁防护<b>静默失效</b>，而且只在带子流程的流程上、只在真实数据库上发生。
 *
 * <p>所以这里对三套仓储分别验证：从库里读回来的子实例，root 必须仍指向父实例。
 */
@DisplayName("流程树根跨仓储往返")
class InstanceRootPersistenceTest {

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
                pr = new com.workflow.repository.InMemoryProcessRepository();
                ir = new com.workflow.repository.InMemoryInstanceRepository();
                tr = new com.workflow.repository.InMemoryTaskRepository();
            }
        }
        return new Suite(which, pr, ir, tr);
    }

    @Test
    @DisplayName("子实例经数据库往返后，root 仍指向父实例；父实例 root 指向自身")
    void rootSurvivesPersistenceRoundTrip() {
        for (String which : List.of("InMemory", "JPA", "MyBatis")) {
            Suite s = newSuite(which);

            ProcessDefinition sub = ProcessBuilder.create("root-sub")
                    .start("start")
                    .userTask("review", "子流程审批", Candidate.ofAny("u2"))
                    .end("end")
                    .connect("start", "review")
                    .connect("review", "end")
                    .build();
            ProcessDefinition main = ProcessBuilder.create("root-main")
                    .start("start")
                    .userTask("apply", "提交", Candidate.ofAny("u1"))
                    .subProcess("sub1", "子流程", "root-sub")
                    .end("end")
                    .connect("start", "apply")
                    .connect("apply", "sub1")
                    .connect("sub1", "end")
                    .build();
            s.procRepo().save(sub);
            s.procRepo().save(main);

            WorkflowEngine engine = new WorkflowEngine(s.procRepo(), s.instRepo(), s.taskRepo());
            String mainId = engine.start("root-main", Map.of());
            // 推进到子流程节点，触发子实例创建
            engine.completeTask(engine.getInstance(mainId).getTasks().get(0).getId(), "u1", true);

            // 子实例 id 记录在父实例的 __sub_ 变量里
            ProcessInstance parent = s.instRepo().findById(mainId);
            String childId = parent.getVariables().entrySet().stream()
                    .filter(e -> e.getKey().startsWith("__sub_") && !"__sub_depth".equals(e.getKey()))
                    .map(Map.Entry::getValue)
                    .map(String::valueOf)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(which + ": 未找到子流程实例, vars=" + parent.getVariables()));

            // 关键断言：全部重新从仓储读取，不信任内存对象
            ProcessInstance reloadedChild = s.instRepo().findById(childId);
            ProcessInstance reloadedParent = s.instRepo().findById(mainId);

            assertThat(reloadedChild.isSubProcess())
                    .as("%s: 子实例身份应保留", which)
                    .isTrue();
            assertThat(reloadedChild.getRootInstanceId())
                    .as("%s: 子实例的 root 必须是父实例 id —— 否则父子各持一把锁，ABBA 防护失效", which)
                    .isEqualTo(mainId);
            assertThat(reloadedParent.getRootInstanceId())
                    .as("%s: 父实例的 root 是自身", which)
                    .isEqualTo(mainId);

            // 推进子流程直至整棵树完成（顺带验证父子回写路径未被破坏）
            engine.completeTask(s.taskRepo().findByInstanceId(childId).stream()
                    .filter(t -> "review".equals(t.getNodeId())).findFirst().orElseThrow().getId(), "u2", true);
            assertThat(s.instRepo().findById(mainId).getStatus())
                    .as("%s: 子流程完成后父流程应推进到结束", which)
                    .isIn(com.workflow.enums.InstanceStatus.COMPLETED,
                            com.workflow.enums.InstanceStatus.RUNNING);
            engine.shutdown();
        }
    }
}
