package com.workflow.tests.engine;

import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用例 12: 子流程嵌入 - 主流程引用子流程定义,子流程完成后主流程继续
 *
 * 核心语义:
 *  - SUB_PROCESS 节点引用另一个流程定义的 key
 *  - 主流程 Token 到达 SUB_PROCESS 时,引擎自动发起子流程实例(继承父变量)
 *  - 子流程完成 -> 回调父流程推进 Token -> 主流程继续
 */
class SubProcessTest extends EngineTestBase {

    @BeforeEach
    void init() { setUp(); }

    private void registerMainAndSub() {
        // 子流程: review -> end
        register(simple("sub")
                .start("start")
                .userTask("review", "子流程审批", any("carol"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build());
        // 主流程: apply -> 子流程 -> end
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
        assertThat(main.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(main.getTasks()).hasSize(1);
        assertThat(main.getTasks().get(0).getNodeId()).isEqualTo("apply");

        // 完成主流程 apply -> 自动发起子流程
        engine.completeTask(main.getTasks().get(0).getId(), "alice", true);

        // 主流程 RUNNING, token 停在 sub1(等待子流程)
        main = engine.getInstance(mainId);
        assertThat(main.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(main.getActiveTokens()).hasSize(1);
        assertThat(main.getActiveTokens().values().iterator().next().getCurrentNodeId())
                .isEqualTo("sub1");

        // 子流程已发起, 有自己的实例和 review 任务
        ProcessInstance child = engine.getInstance(main.getVariable("__sub_" + main.getActiveTokens().values().iterator().next().getId()).toString());
        assertThat(child).isNotNull();
        assertThat(child.isSubProcess()).isTrue();
        assertThat(child.getParentInstanceId()).isEqualTo(mainId);
        assertThat(child.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(child.getTasks()).hasSize(1);
        assertThat(child.getTasks().get(0).getNodeId()).isEqualTo("review");
        assertThat(child.getTasks().get(0).getStatus()).isEqualTo(TaskStatus.PENDING);

        // 完成子流程 review -> 子流程完成,主流程自动推进到 end 并完成
        engine.completeTask(child.getTasks().get(0).getId(), "carol", true);

        assertThat(engine.getInstance(child.getId()).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        main = engine.getInstance(mainId);
        assertThat(main.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void sub_process_variable_inherit() {
        // 子流程带条件网关,用父流程变量 amount 决定分支
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

        // 父变量 amount=100 -> 子流程走 approve 分支
        String mainId = engine.start("mainv", Map.of("amount", 100));
        ProcessInstance child = findChild(mainId);
        assertThat(child.getTasks()).hasSize(1);
        assertThat(child.getTasks().get(0).getNodeId()).isEqualTo("approve");
        engine.completeTask(child.getTasks().get(0).getId(), "carol", true);
        assertThat(engine.getInstance(child.getId()).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mainId).getStatus()).isEqualTo(InstanceStatus.COMPLETED);

        // 父变量 amount=10 -> 子流程直接走 end
        String mainId2 = engine.start("mainv", Map.of("amount", 10));
        ProcessInstance child2 = findChild(mainId2);
        assertThat(child2.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(engine.getInstance(mainId2).getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void sub_process_reject_inside() {
        // 子流程: apply(提交) -> review(审核); review 驳回后回到 apply 重新提交
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
        ProcessInstance child = findChild(mainId);

        // 子流程 apply 完成 -> review 任务
        engine.completeTask(child.getTasks().get(0).getId(), "carol", true);
        child = engine.getInstance(child.getId());
        TaskInstance reviewTask = child.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElse(null);
        assertThat(reviewTask).isNotNull();
        assertThat(reviewTask.getNodeId()).isEqualTo("review");

        // review 驳回 -> 回到 apply 重新生成待办
        engine.rejectTask(reviewTask.getId(), "dave", "材料不全");
        child = engine.getInstance(child.getId());
        assertThat(child.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        TaskInstance pending = child.getTasks().stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING).findFirst().orElse(null);
        assertThat(pending).isNotNull();
        assertThat(pending.getNodeId()).isEqualTo("apply");

        // 重新提交 -> review -> 通过 -> 子流程完成 -> 主流程完成
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
        // 三层: main -> mid -> leaf
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
        ProcessInstance mid = findChild(mainId);
        ProcessInstance leaf = findChild(mid.getId());
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
        // 子流程 key 未注册 -> 发起时报错
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

    private ProcessInstance findChild(String parentId) {
        ProcessInstance parent = engine.getInstance(parentId);
        // 找第一个子流程发起标记变量(__sub_<tokenId> = childInstanceId),排除 __sub_depth
        for (Map.Entry<String, Object> e : parent.getVariables().entrySet()) {
            if (e.getKey().startsWith("__sub_") && !e.getKey().equals("__sub_depth")) {
                return engine.getInstance(e.getValue().toString());
            }
        }
        throw new AssertionError("未找到子流程实例, variables=" + parent.getVariables());
    }
}
