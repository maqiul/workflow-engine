package com.workflow.tests.jpa;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.NodeType;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import com.workflow.topology.InstanceTopologyView;
import com.workflow.topology.NodeView;
import com.workflow.topology.TopologyView;
import com.workflow.topology.TransitionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA 拓扑自省测试 - 验证拓扑视图经持久化仓储装配后行为一致
 */
@DisplayName("JPA 拓扑自省")
class JpaTopologyViewTest extends JpaEngineTestBase {

    @Test
    @DisplayName("JPA: 拓扑视图 + 实例高亮")
    void topologyRoundTrip() {
        ProcessDefinition def = ProcessBuilder.create("leave-flow", "请假审批")
                .version(1)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();
        procRepo.save(def);

        // 定义拓扑（经 DB JSON 往返）
        TopologyView topo = engine.getTopology("leave-flow", 1);
        assertThat(topo.getProcessKey()).isEqualTo("leave-flow");
        assertThat(topo.getVersion()).isEqualTo(1);
        assertThat(topo.getNodes()).hasSize(4);
        assertThat(topo.getTransitions()).hasSize(3);

        NodeView apply = topo.getNodes().stream()
                .filter(n -> "apply".equals(n.getId())).findFirst().orElseThrow();
        assertThat(apply.getType()).isEqualTo(NodeType.USER_TASK);
        assertThat(apply.getUserIds()).containsExactly("user1");

        TransitionView t = topo.getTransitions().stream()
                .filter(x -> "apply".equals(x.getFrom()) && "manager".equals(x.getTo()))
                .findFirst().orElseThrow();
        assertThat(t.getCondition()).isNull();

        // 实例拓扑（经 DB 重建 instance/token/task）
        String instanceId = engine.start("leave-flow", 1, Map.of());

        InstanceTopologyView first = engine.getInstanceTopology(instanceId);
        assertThat(first.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(first.getActiveNodeIds()).containsExactly("apply");
        assertThat(first.getCompletedNodeIds()).isEmpty();

        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(x -> "apply".equals(x.getNodeId()))
                .findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "user1", true);

        InstanceTopologyView second = engine.getInstanceTopology(instanceId);
        assertThat(second.getActiveNodeIds()).containsExactly("manager");
        assertThat(second.getCompletedNodeIds()).containsExactly("apply");
    }

    @Test
    @DisplayName("JPA: 拓扑不存在抛异常")
    void topologyNotFoundThrows() {
        assertThatThrownBy(() -> engine.getTopology("no-such", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("流程定义不存在");
    }
}
