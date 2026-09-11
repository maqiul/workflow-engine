package com.workflow.tests.engine;

import com.workflow.bpmn.BpmnException;
import com.workflow.bpmn.BpmnExporter;
import com.workflow.bpmn.BpmnImportDiagnostics;
import com.workflow.bpmn.BpmnImporter;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flowable BPMN 导入兼容性。
 *
 * <p>覆盖三类曾经的缺陷：
 * <ul>
 *   <li><b>硬失败</b>：candidateGroups / 静态 assignee 曾直接抛异常，整份定义导不进来</li>
 *   <li><b>静默降级</b>：多实例会签曾被当成单人任务导入，流程能跑但语义错</li>
 *   <li><b>静默丢弃</b>：未知 flowable 属性曾无影无踪，调用方无从察觉</li>
 * </ul>
 */
@DisplayName("Flowable BPMN 导入兼容性")
class FlowableImportCompatibilityTest {

    private static final String FLOWABLE_NS = "http://flowable.org/bpmn";

    // ---------- 审批人来源 ----------

    @Test
    @DisplayName("静态 flowable:assignee 解析为单候选人（曾硬失败）")
    void staticAssignee() {
        ProcessDefinition def = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="静态指派" flowable:assignee="zhangsan"/>
                """));

        var node = def.getNode("task1");
        assertThat(node.getType()).isEqualTo(NodeType.USER_TASK);
        assertThat(node.hasAssigneeVariable()).isFalse();
        assertThat(node.getCandidate().getUserIds()).containsExactly("zhangsan");
        assertThat(node.getCandidate().getStrategy()).isEqualTo(CandidateStrategy.ANY);
    }

    @Test
    @DisplayName("flowable:assignee=${var} 仍是动态办理人（回归保护）")
    void dynamicAssignee() {
        ProcessDefinition def = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="动态指派" flowable:assignee="${applicant}"/>
                """));

        var node = def.getNode("task1");
        assertThat(node.hasAssigneeVariable()).isTrue();
        assertThat(node.getAssigneeVariable()).isEqualTo("applicant");
    }

    @Test
    @DisplayName("flowable:candidateUsers 解析为 ANY 候选池")
    void candidateUsers() {
        ProcessDefinition def = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="候选池" flowable:candidateUsers="u1,u2,u3"/>
                """));

        var candidate = def.getNode("task1").getCandidate();
        assertThat(candidate.getUserIds()).containsExactlyInAnyOrder("u1", "u2", "u3");
        assertThat(candidate.getStrategy()).isEqualTo(CandidateStrategy.ANY);
    }

    @Test
    @DisplayName("flowable:candidateGroups 解析为候选组（groupIds），不再混进 userIds")
    void candidateGroups() {
        BpmnImportDiagnostics diag = new BpmnImportDiagnostics();
        ProcessDefinition def = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="候选组" flowable:candidateGroups="ROLE_MGR,ROLE_HR"/>
                """), diag);

        // 组名既不静默丢弃，也不当成用户：早先把它并进 userIds，
        // 模型层就分不清人与组，组名会被拿去匹配"能不能办"（永远匹配不上）
        var candidate = def.getNode("task1").getCandidate();
        assertThat(candidate.getGroupIds()).containsExactlyInAnyOrder("ROLE_MGR", "ROLE_HR");
        assertThat(candidate.getUserIds()).isEmpty();
        assertThat(candidate.isUnresolved()).isTrue();

        assertThat(diag.getWarnings())
                .anyMatch(w -> w.contains("candidateGroups") && w.contains("ROLE_MGR"));
    }

    @Test
    @DisplayName("assignee 与 candidateUsers 并存时 assignee 优先，并留下诊断")
    void assigneeWinsOverCandidatePool() {
        BpmnImportDiagnostics diag = new BpmnImportDiagnostics();
        ProcessDefinition def = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="两者并存" flowable:assignee="boss" flowable:candidateUsers="u1,u2"/>
                """), diag);

        assertThat(def.getNode("task1").getCandidate().getUserIds()).containsExactly("boss");
        assertThat(diag.getWarnings()).anyMatch(w -> w.contains("assignee 优先"));
    }

    // ---------- 多实例会签 / 或签 ----------

    @Test
    @DisplayName("flowable:collection 映射为 MULTI_INSTANCE，或签条件生效")
    void multiInstanceAnySign() {
        ProcessDefinition def = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="会签">
                  <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                        flowable:collection="${approvers}" flowable:elementVariable="approver">
                    <bpmn:completionCondition>${nrOfCompletedInstances >= 1}</bpmn:completionCondition>
                  </bpmn:multiInstanceLoopCharacteristics>
                </bpmn:userTask>"""));

        var node = def.getNode("task1");
        assertThat(node.getType()).isEqualTo(NodeType.MULTI_INSTANCE);
        assertThat(node.getMultiInstanceCollection()).isEqualTo("approvers");
        assertThat(node.getMultiInstanceStrategy()).isEqualTo(CandidateStrategy.ANY);
    }

    @Test
    @DisplayName("无完成条件的多实例判定为会签（ALL，Flowable 默认语义）")
    void multiInstanceAllSign() {
        ProcessDefinition def = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="会签">
                  <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                        flowable:collection="${approvers}"/>
                </bpmn:userTask>"""));

        var node = def.getNode("task1");
        assertThat(node.getType()).isEqualTo(NodeType.MULTI_INSTANCE);
        assertThat(node.getMultiInstanceStrategy()).isEqualTo(CandidateStrategy.ALL);
    }

    @Test
    @DisplayName("多实例可导出回 BPMN 并再次导入（往返对称，曾 UnsupportedOperationException）")
    void multiInstanceRoundTrip() {
        ProcessDefinition original = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="会签">
                  <bpmn:multiInstanceLoopCharacteristics isSequential="false"
                        flowable:collection="${approvers}">
                    <bpmn:completionCondition>${nrOfCompletedInstances >= 1}</bpmn:completionCondition>
                  </bpmn:multiInstanceLoopCharacteristics>
                </bpmn:userTask>"""));

        String bpmn = BpmnExporter.export(original);
        assertThat(bpmn).contains("flowable:collection=\"${approvers}\"");

        var roundTripped = BpmnImporter.importFrom(bpmn).getNode("task1");
        assertThat(roundTripped.getType()).isEqualTo(NodeType.MULTI_INSTANCE);
        assertThat(roundTripped.getMultiInstanceCollection()).isEqualTo("approvers");
        assertThat(roundTripped.getMultiInstanceStrategy()).isEqualTo(CandidateStrategy.ANY);
    }

    @Test
    @DisplayName("顺序多实例产生语义降级诊断（引擎为并行展开）")
    void sequentialMultiInstanceWarns() {
        BpmnImportDiagnostics diag = new BpmnImportDiagnostics();
        BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="顺序会签">
                  <bpmn:multiInstanceLoopCharacteristics isSequential="true"
                        flowable:collection="${approvers}"/>
                </bpmn:userTask>"""), diag);

        assertThat(diag.getWarnings()).anyMatch(w -> w.contains("顺序多实例"));
    }

    // ---------- 不静默丢弃 ----------

    @Test
    @DisplayName("未知 flowable 属性记入诊断而非静默丢弃")
    void unsupportedFlowableAttributesAreReported() {
        BpmnImportDiagnostics diag = new BpmnImportDiagnostics();
        BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="带表单" flowable:assignee="u1"
                               flowable:formKey="leaveForm" flowable:priority="50"/>"""), diag);

        assertThat(diag.getWarnings())
                .anyMatch(w -> w.contains("flowable:formKey"))
                .anyMatch(w -> w.contains("flowable:priority"));
    }

    @Test
    @DisplayName("不支持的 BPMN 元素记入诊断")
    void unsupportedElementIsReported() {
        BpmnImportDiagnostics diag = new BpmnImportDiagnostics();
        BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="正常" flowable:assignee="u1"/>
                <bpmn:scriptTask id="script1" scriptFormat="groovy"/>
                """), diag);

        assertThat(diag.getWarnings()).anyMatch(w -> w.contains("scriptTask"));
    }

    @Test
    @DisplayName("被忽略的节点导致校验失败时，报错点出真正的元凶")
    void skippedNodeExplainsValidationFailure() {
        assertThatThrownBy(() -> BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="正常" flowable:assignee="u1"/>
                <bpmn:scriptTask id="script1" scriptFormat="groovy"/>
                <bpmn:sequenceFlow id="f3" sourceRef="script1" targetRef="task1"/>
                """)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("script1");
    }

    @Test
    @DisplayName("单参 importFrom 不产生诊断对象时不抛异常（日志路径）")
    void singleArgOverloadStillWorks() {
        ProcessDefinition def = BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="普通" flowable:assignee="u1" flowable:category="x"/>
                """));

        assertThat(def.getNode("task1")).isNotNull();
    }

    // ---------- 错误信息可操作 ----------

    @Test
    @DisplayName("无任何审批人来源时报错并列出支持的形式")
    void noApproverProducesActionableError() {
        assertThatThrownBy(() -> BpmnImporter.importFrom(processWith("""
                <bpmn:userTask id="task1" name="无审批人"/>
                """)))
                .isInstanceOf(BpmnException.class)
                .hasMessageContaining("没有任何可用的审批人定义")
                .hasMessageContaining("candidateGroups");
    }

    // ---------- 工具 ----------

    /** 把被测 userTask 套进一份完整可执行的 BPMN 定义。 */
    private static String processWith(String taskXml) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:flowable="%s"
                                  targetNamespace="%s">
                  <bpmn:process id="p" name="兼容性测试">
                    %s
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="task1"/>
                    <bpmn:sequenceFlow id="f2" sourceRef="task1" targetRef="end"/>
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(FLOWABLE_NS, FLOWABLE_NS, taskXml);
    }
}
