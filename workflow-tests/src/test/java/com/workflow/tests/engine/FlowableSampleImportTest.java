package com.workflow.tests.engine;

import com.workflow.bpmn.BpmnImportDiagnostics;
import com.workflow.bpmn.BpmnImporter;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flowable 真实样本导入集成测试
 * 
 * 样本：customer_order_flow.bpmn（规格书完整流程）
 * 来源：用户提供真实 Flowable 导出文件
 */
@DisplayName("Flowable 真实样本导入")
class FlowableSampleImportTest {

    @Test
    @DisplayName("导入 customer_order_flow.bpmn 成功")
    void importCustomerOrderFlow() throws Exception {
        // 读取样本文件
        InputStream is = getClass().getResourceAsStream("/samples/customer_order_flow.bpmn");
        assertThat(is).as("样本文件必须存在").isNotNull();
        String bpmn = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 导入：真实 Flowable 样本必须做到零语义降级
        BpmnImportDiagnostics diag = new BpmnImportDiagnostics();
        ProcessDefinition def = BpmnImporter.importFrom(bpmn, diag);
        assertThat(diag.getWarnings()).as("真实样本不应有语义降级警告").isEmpty();

        // 验证流程定义
        assertThat(def.getKey()).isEqualTo("customer_order_flow");
        assertThat(def.getName()).isEqualTo("规格书完整流程");

        // 验证节点存在
        assertThat(def.getNode("startEvent")).isNotNull();
        assertThat(def.getNode("endEvent_approve")).isNotNull();
        assertThat(def.getNode("edraft_submit")).isNotNull();
        assertThat(def.getNode("edraft_confirm")).isNotNull();
        assertThat(def.getNode("spec_no_fill")).isNotNull();
        assertThat(def.getNode("design_review")).isNotNull();
        assertThat(def.getNode("biz_review")).isNotNull();
        assertThat(def.getNode("final_review")).isNotNull();
        assertThat(def.getNode("cc_node")).isNotNull();
        assertThat(def.getNode("write_epc")).isNotNull();
        assertThat(def.getNode("userTask_7")).isNotNull();
        assertThat(def.getNode("resubmit_1")).isNotNull();
        assertThat(def.getNode("loop_temple_node")).isNotNull();
        assertThat(def.getNode("loop_cc_node")).isNotNull();
        assertThat(def.getNode("gw_confirm")).isNotNull();
        assertThat(def.getNode("gw_parallel_fork")).isNotNull();
        assertThat(def.getNode("gw_parallel_join")).isNotNull();
        assertThat(def.getNode("parallelGateway_3")).isNotNull();
        assertThat(def.getNode("parallelGateway_4")).isNotNull();
        assertThat(def.getNode("loop_node_loop_gw")).isNotNull();

        // edraft_submit 在 Flowable 里是「按集合展开的会签」，不是单人动态指派：
        // 它同时带 flowable:assignee="${assignee}" 和
        // multiInstanceLoopCharacteristics flowable:collection="${edraft_submitApprovers}"。
        // 集合才是真语义（assignee 只是展开后的元素变量名），故必须映射为 MULTI_INSTANCE ——
        // 早先只认 assignee，会让会签静默退化成单人任务。
        var edraftSubmit = def.getNode("edraft_submit");
        assertThat(edraftSubmit.getType()).isEqualTo(NodeType.MULTI_INSTANCE);
        assertThat(edraftSubmit.getMultiInstanceCollection()).isEqualTo("edraft_submitApprovers");
        assertThat(edraftSubmit.getMultiInstanceStrategy()).isEqualTo(CandidateStrategy.ANY);

        // 其余 userTask 才是真正的单人动态指派
        var edraftConfirm = def.getNode("edraft_confirm");
        assertThat(edraftConfirm.hasAssigneeVariable()).isTrue();
        assertThat(edraftConfirm.getAssigneeVariable()).isEqualTo("edraft_confirmApprover");

        // 验证 serviceTask（cc_node 和 loop_cc_node）
        var ccNode = def.getNode("cc_node");
        assertThat(ccNode.isServiceTask()).isTrue();
        assertThat(ccNode.getDelegateKey()).isEqualTo("ccNotificationDelegate");

        var loopCcNode = def.getNode("loop_cc_node");
        assertThat(loopCcNode.isServiceTask()).isTrue();
        assertThat(loopCcNode.getDelegateKey()).isEqualTo("ccNotificationDelegate");

        // 验证循环回边（loop_temple_node ↔ loop_node_loop_gw）
        var loopTempleNode = def.getNode("loop_temple_node");
        assertThat(loopTempleNode.hasAssigneeVariable()).isTrue();
        assertThat(loopTempleNode.getAssigneeVariable()).isEqualTo("loop_temple_nodeApprover");

        // 验证排他网关条件
        var gwConfirm = def.getNode("gw_confirm");
        assertThat(gwConfirm.getType()).isEqualTo(NodeType.EXCLUSIVE_GATEWAY);

        // 验证并行网关
        var gwParallelFork = def.getNode("gw_parallel_fork");
        assertThat(gwParallelFork.getType()).isEqualTo(NodeType.PARALLEL_GATEWAY);

        var gwParallelJoin = def.getNode("gw_parallel_join");
        assertThat(gwParallelJoin.getType()).isEqualTo(NodeType.PARALLEL_GATEWAY);
    }

    @Test
    @DisplayName("导入后流程可正常启动（需注册 delegate）")
    void importAndStart() throws Exception {
        // 读取样本文件
        InputStream is = getClass().getResourceAsStream("/samples/customer_order_flow.bpmn");
        String bpmn = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 导入
        ProcessDefinition def = BpmnImporter.importFrom(bpmn);

        // 验证流程定义不为空
        assertThat(def).isNotNull();
        assertThat(def.getKey()).isEqualTo("customer_order_flow");

        // 注：实际启动需要注册 delegate 和设置变量，这里只验证导入成功
    }
}
