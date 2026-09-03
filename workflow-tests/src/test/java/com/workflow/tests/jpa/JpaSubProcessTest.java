package com.workflow.tests.jpa;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 版子流程嵌入测试 - 与 InMemory 版完全同组用例
 */
class JpaSubProcessTest extends JpaEngineTestBase {

    private void registerMainAndSub() {
        register(simple("sub")
                .start("start")
                .userTask("review", "子流程审批", any("carol"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build());
        register(simple("main")
                .start("start")
                .userTask("apply", "提交", any("alice"))
                .subProcess("sub1", "子流程", "sub")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "sub1")
                .connect("sub1", "end")
                .build());
    }

    @Test
    void sub_process_flow_simple() {
        registerMainAndSub();

        String mainId = engine.start("main", Map.of());
        ProcessInstance main = engine.getInstance(mainId);
        engine.completeTask(main.getTasks().get(0).getId(), "alice", true);

        main = engine.getInstance(mainId);
        assertThat(main.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(main.getActiveTokens()).hasSize(1);
        assertThat(main.getActiveTokens().values().iterator().next().getCurrentNodeId())
                .isEqualTo("sub1");

        ProcessInstance child = findChild(main);
        assertThat(child).isNotNull();
        assertThat(child.isSubProcess()).isTrue();
        assertThat(child.getParentInstanceId()).isEqualTo(mainId);
        assertThat(child.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(child.getTasks()).hasSize(1);
        assertThat(child.getTasks().get(0).getNodeId()).isEqualTo("review");

        engine.completeTask(child.getTasks().get(0).getId(), "carol", true);

        assertThat(engine.getInstance(child.getId()).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mainId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void sub_process_variable_inherit() {
        register(simple("subv")
                .start("start")
                .exclusiveGateway("gw")
                .userTask("approve", "大额审批", any("carol"))
                .end("end")
                .connect("start", "gw")
                .connect("gw", "approve", "amount > 50")
                .connect("gw", "end", "amount <= 50")
                .connect("approve", "end")
                .build());
        register(simple("mainv")
                .start("start")
                .subProcess("sub1", "子流程", "subv")
                .end("end")
                .connect("start", "sub1")
                .connect("sub1", "end")
                .build());

        String mainId = engine.start("mainv", Map.of("amount", 100));
        ProcessInstance child = findChild(engine.getInstance(mainId));
        assertThat(child.getTasks()).hasSize(1);
        assertThat(child.getTasks().get(0).getNodeId()).isEqualTo("approve");
        engine.completeTask(child.getTasks().get(0).getId(), "carol", true);
        assertThat(engine.getInstance(child.getId()).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mainId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);

        String mainId2 = engine.start("mainv", Map.of("amount", 10));
        ProcessInstance child2 = findChild(engine.getInstance(mainId2));
        assertThat(child2.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mainId2).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void sub_process_reject_inside() {
        register(simple("subr")
                .start("start")
                .userTask("apply", "子流程提交", any("carol"))
                .userTask("review", "子流程审批", any("dave"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build());
        register(simple("mainr")
                .start("start")
                .subProcess("sub1", "子流程", "subr")
                .end("end")
                .connect("start", "sub1")
                .connect("sub1", "end")
                .build());

        String mainId = engine.start("mainr", Map.of());
        ProcessInstance child = findChild(engine.getInstance(mainId));
        engine.completeTask(child.getTasks().get(0).getId(), "carol", true);
        child = engine.getInstance(child.getId());
        TaskInstance reviewTask = child.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElse(null);
        assertThat(reviewTask).isNotNull();
        assertThat(reviewTask.getNodeId()).isEqualTo("review");

        engine.rejectTask(reviewTask.getId(), "dave", "材料不全");
        child = engine.getInstance(child.getId());
        assertThat(child.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        TaskInstance pending = child.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElse(null);
        assertThat(pending).isNotNull();
        assertThat(pending.getNodeId()).isEqualTo("apply");

        engine.completeTask(pending.getId(), "carol", true);
        child = engine.getInstance(child.getId());
        TaskInstance review2 = child.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElse(null);
        assertThat(review2).isNotNull();
        engine.completeTask(review2.getId(), "dave", true);
        assertThat(engine.getInstance(child.getId()).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mainId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void sub_process_nested() {
        register(simple("leaf")
                .start("start")
                .userTask("leafReview", "叶子审批", any("dave"))
                .end("end")
                .connect("start", "leafReview")
                .connect("leafReview", "end")
                .build());
        register(simple("mid")
                .start("start")
                .subProcess("s2", "叶子子流程", "leaf")
                .end("end")
                .connect("start", "s2")
                .connect("s2", "end")
                .build());
        register(simple("main")
                .start("start")
                .subProcess("s1", "中间子流程", "mid")
                .end("end")
                .connect("start", "s1")
                .connect("s1", "end")
                .build());

        String mainId = engine.start("main", Map.of());
        ProcessInstance mid = findChild(engine.getInstance(mainId));
        ProcessInstance leaf = findChild(engine.getInstance(mid.getId()));
        assertThat(leaf.isSubProcess()).isTrue();
        assertThat(leaf.getParentInstanceId()).isEqualTo(mid.getId());
        assertThat(leaf.getTasks()).hasSize(1);

        engine.completeTask(leaf.getTasks().get(0).getId(), "dave", true);
        assertThat(engine.getInstance(leaf.getId()).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mid.getId()).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mainId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void sub_process_missing_def_throws() {
        register(simple("mainx")
                .start("start")
                .subProcess("sub1", "子流程", "not_exist")
                .end("end")
                .connect("start", "sub1")
                .connect("sub1", "end")
                .build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.start("mainx", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not_exist");
    }

    private ProcessInstance findChild(ProcessInstance parent) {
        for (Map.Entry<String, Object> e : parent.getVariables().entrySet()) {
            if (e.getKey().startsWith("__sub_") && !e.getKey().equals("__sub_depth")) {
                return engine.getInstance(e.getValue().toString());
            }
        }
        throw new AssertionError("未找到子流程实例, variables=" + parent.getVariables());
    }
}
