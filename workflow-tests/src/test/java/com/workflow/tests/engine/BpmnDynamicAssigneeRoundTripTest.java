package com.workflow.tests.engine;

import com.workflow.bpmn.BpmnExporter;
import com.workflow.bpmn.BpmnImporter;
import com.workflow.definition.ProcessDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BPMN 动态 assignee 导出/导入往返测试
 */
@DisplayName("BPMN 动态 assignee 往返")
class BpmnDynamicAssigneeRoundTripTest {

    @Test
    @DisplayName("动态 assignee 导出为 flowable:assignee，导入后还原")
    void roundTrip() {
        ProcessDefinition original = com.workflow.builder.ProcessBuilder.create("bpmn-dynamic-assignee")
                .start("start")
                .userTask("apply", "申请", "applyApprover")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();

        // 导出
        String bpmn = BpmnExporter.export(original);
        assertThat(bpmn).contains("flowable:assignee=\"${applyApprover}\"");

        // 导入
        ProcessDefinition imported = BpmnImporter.importFrom(bpmn);

        // 验证
        assertThat(imported.getKey()).isEqualTo("bpmn-dynamic-assignee");
        var applyNode = imported.getNode("apply");
        assertThat(applyNode).isNotNull();
        assertThat(applyNode.hasAssigneeVariable()).isTrue();
        assertThat(applyNode.getAssigneeVariable()).isEqualTo("applyApprover");
        assertThat(applyNode.getCandidate()).isNull();
    }

    @Test
    @DisplayName("静态 candidate 不受影响")
    void staticCandidateUnaffected() {
        ProcessDefinition original = com.workflow.builder.ProcessBuilder.create("bpmn-static-candidate")
                .start("start")
                .userTask("apply", "申请", com.workflow.definition.Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();

        String bpmn = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(bpmn);

        var applyNode = imported.getNode("apply");
        assertThat(applyNode.hasAssigneeVariable()).isFalse();
        assertThat(applyNode.getCandidate()).isNotNull();
        assertThat(applyNode.getCandidate().getUserIds()).containsExactly("user1");
    }
}
