package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.NodeType;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.TaskInstance;
import com.workflow.topology.InstanceTopologyView;
import com.workflow.topology.NodeView;
import com.workflow.topology.TopologyView;
import com.workflow.topology.TransitionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 拓扑自省（getBpmnModel 等价能力）测试 - InMemory
 */
@DisplayName("拓扑自省")
class TopologyViewTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo).build();
    }

    private ProcessDefinition serialFlow() {
        return ProcessBuilder.create("leave-flow", "请假审批")
                .version(1)
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("user1"))
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "end")
                .build();
    }

    private ProcessDefinition conditionalFlow() {
        return ProcessBuilder.create("purchase", "采购审批")
                .version(1)
                .start("start")
                .exclusiveGateway("gw")
                .userTask("manager", "经理审批", Candidate.ofAny("m1"))
                .userTask("director", "总监审批", Candidate.ofAny("d1"))
                .end("end")
                .connect("start", "gw")
                .connect("gw", "manager", "amount < 1000")
                .connect("gw", "director", "amount >= 1000")
                .connect("manager", "end")
                .connect("director", "end")
                .build();
    }

    @Test
    @DisplayName("基本拓扑：节点与连线")
    void basicTopology() {
        procRepo.save(serialFlow());

        TopologyView topo = engine.getTopology("leave-flow", 1);

        assertThat(topo.getProcessKey()).isEqualTo("leave-flow");
        assertThat(topo.getVersion()).isEqualTo(1);
        assertThat(topo.getName()).isEqualTo("请假审批");
        assertThat(topo.getNodes()).hasSize(4);
        assertThat(topo.getTransitions()).hasSize(3);

        NodeView apply = topo.getNodes().stream()
                .filter(n -> "apply".equals(n.getId())).findFirst().orElseThrow();
        assertThat(apply.getType()).isEqualTo(NodeType.USER_TASK);
        assertThat(apply.getName()).isEqualTo("申请");
        assertThat(apply.getUserIds()).containsExactly("user1");

        NodeView start = topo.getNodes().stream()
                .filter(n -> "start".equals(n.getId())).findFirst().orElseThrow();
        assertThat(start.getType()).isEqualTo(NodeType.START);
        assertThat(start.getUserIds()).isEmpty();

        TransitionView t = topo.getTransitions().stream()
                .filter(x -> "apply".equals(x.getFrom()) && "manager".equals(x.getTo()))
                .findFirst().orElseThrow();
        assertThat(t.getCondition()).isNull();
    }

    @Test
    @DisplayName("条件网关连线暴露条件表达式")
    void conditionalGatewayTopology() {
        procRepo.save(conditionalFlow());

        TopologyView topo = engine.getTopology("purchase", 1);

        TransitionView toManager = topo.getTransitions().stream()
                .filter(x -> "gw".equals(x.getFrom()) && "manager".equals(x.getTo()))
                .findFirst().orElseThrow();
        assertThat(toManager.getCondition()).isEqualTo("amount < 1000");

        TransitionView toDirector = topo.getTransitions().stream()
                .filter(x -> "gw".equals(x.getFrom()) && "director".equals(x.getTo()))
                .findFirst().orElseThrow();
        assertThat(toDirector.getCondition()).isEqualTo("amount >= 1000");
    }

    @Test
    @DisplayName("动态 assignee 与 serviceTask 字段暴露")
    void dynamicAssigneeAndServiceTaskExposed() {
        ProcessDefinition flow = ProcessBuilder.create("mixed", "混合流程")
                .version(1)
                .start("start")
                .userTask("dyn", "动态审批", "approver")
                .userTask("static", "静态审批", Candidate.ofAny("u1"))
                .serviceTask("svc", "服务任务", "myDelegate")
                .end("end")
                .connect("start", "dyn")
                .connect("dyn", "static")
                .connect("static", "svc")
                .connect("svc", "end")
                .build();
        procRepo.save(flow);

        TopologyView topo = engine.getTopology("mixed", 1);

        NodeView dyn = topo.getNodes().stream()
                .filter(n -> "dyn".equals(n.getId())).findFirst().orElseThrow();
        assertThat(dyn.getAssigneeVariable()).isEqualTo("approver");
        assertThat(dyn.getUserIds()).isEmpty();

        NodeView stat = topo.getNodes().stream()
                .filter(n -> "static".equals(n.getId())).findFirst().orElseThrow();
        assertThat(stat.getUserIds()).containsExactly("u1");
        assertThat(stat.getAssigneeVariable()).isNull();

        NodeView svc = topo.getNodes().stream()
                .filter(n -> "svc".equals(n.getId())).findFirst().orElseThrow();
        assertThat(svc.getType()).isEqualTo(NodeType.SERVICE_TASK);
        assertThat(svc.getDelegateKey()).isEqualTo("myDelegate");
    }

    @Test
    @DisplayName("实例拓扑：高亮当前节点与已完成节点")
    void instanceTopologyHighlightsNodes() {
        procRepo.save(serialFlow());

        String instanceId = engine.start("leave-flow", 1, Map.of());

        // 启动后 Token 停在 apply
        InstanceTopologyView first = engine.getInstanceTopology(instanceId);
        assertThat(first.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(first.getActiveNodeIds()).containsExactly("apply");
        assertThat(first.getCompletedNodeIds()).isEmpty();
        assertThat(first.getTopology().getProcessKey()).isEqualTo("leave-flow");

        // 完成 apply → Token 推进到 manager
        TaskInstance applyTask = taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "apply".equals(t.getNodeId()))
                .findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "user1", true);

        InstanceTopologyView second = engine.getInstanceTopology(instanceId);
        assertThat(second.getActiveNodeIds()).containsExactly("manager");
        assertThat(second.getCompletedNodeIds()).containsExactly("apply");
    }

    @Test
    @DisplayName("version=-1 取最新版")
    void latestVersionViaMinusOne() {
        procRepo.save(ProcessBuilder.create("vflow", "流程")
                .version(1)
                .start("start")
                .userTask("a", "A", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "a")
                .connect("a", "end")
                .build());
        procRepo.save(ProcessBuilder.create("vflow", "流程")
                .version(2)
                .start("start")
                .userTask("a", "A", Candidate.ofAny("u1"))
                .userTask("b", "B", Candidate.ofAny("u2"))
                .end("end")
                .connect("start", "a")
                .connect("a", "b")
                .connect("b", "end")
                .build());

        TopologyView topo = engine.getTopology("vflow", -1);
        assertThat(topo.getVersion()).isEqualTo(2);
        assertThat(topo.getNodes()).hasSize(4);
    }

    @Test
    @DisplayName("拓扑不存在抛异常")
    void topologyNotFoundThrows() {
        assertThatThrownBy(() -> engine.getTopology("no-such", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("流程定义不存在");
    }
}
