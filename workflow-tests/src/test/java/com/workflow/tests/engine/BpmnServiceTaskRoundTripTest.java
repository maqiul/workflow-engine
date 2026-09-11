package com.workflow.tests.engine;

import com.workflow.bpmn.BpmnExporter;
import com.workflow.bpmn.BpmnImporter;
import com.workflow.definition.ProcessDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BPMN serviceTask 导出/导入往返测试
 */
@DisplayName("BPMN serviceTask 往返")
class BpmnServiceTaskRoundTripTest {

    @Test
    @DisplayName("serviceTask 导出为 wf:delegate，导入后还原")
    void roundTrip() {
        ProcessDefinition original = com.workflow.builder.ProcessBuilder.create("bpmn-service-task")
                .start("start")
                .userTask("apply", "申请", com.workflow.definition.Candidate.ofAny("user1"))
                .serviceTask("notify", "发送通知", "sendNotification")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "notify")
                .connect("notify", "end")
                .build();

        // 导出
        String bpmn = BpmnExporter.export(original);
        assertThat(bpmn).contains("<serviceTask");
        assertThat(bpmn).contains("wf:delegate");
        assertThat(bpmn).contains("key=\"sendNotification\"");

        // 导入
        ProcessDefinition imported = BpmnImporter.importFrom(bpmn);

        // 验证
        assertThat(imported.getKey()).isEqualTo("bpmn-service-task");
        var notifyNode = imported.getNode("notify");
        assertThat(notifyNode).isNotNull();
        assertThat(notifyNode.isServiceTask()).isTrue();
        assertThat(notifyNode.getDelegateKey()).isEqualTo("sendNotification");
    }

    @Test
    @DisplayName("userTask 不受影响")
    void userTaskUnaffected() {
        ProcessDefinition original = com.workflow.builder.ProcessBuilder.create("bpmn-user-task")
                .start("start")
                .userTask("apply", "申请", com.workflow.definition.Candidate.ofAny("user1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();

        String bpmn = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(bpmn);

        var applyNode = imported.getNode("apply");
        assertThat(applyNode.isServiceTask()).isFalse();
        assertThat(applyNode.getCandidate()).isNotNull();
        assertThat(applyNode.getCandidate().getUserIds()).containsExactly("user1");
    }
}
