package com.workflow.bpmn;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.enums.CandidateStrategy;
import com.workflow.enums.TimeoutPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BPMN 双向映射测试。
 *
 * <p>核心原则：导入必须走 {@link ProcessBuilder#build()} 的校验，
 * 不能直接构造 ProcessDefinition —— 否则就是我们在 REST 层刚拒绝过的
 * "第二条无校验入口"。
 */
@DisplayName("BPMN 双向映射")
class BpmnRoundTripTest {

    @Test
    @DisplayName("简单串行流程：导出再导入应等价")
    void simpleSerialFlow() {
        ProcessDefinition original = ProcessBuilder.create("leave", "请假审批")
                .start("start")
                .userTask("apply", "提交申请", Candidate.ofAny("employee"))
                .userTask("manager", "经理审批", Candidate.ofAny("managerA", "managerB"))
                .userTask("hr", "HR 审批", Candidate.ofAny("hr"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "hr")
                .connect("hr", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getKey()).isEqualTo("leave");
        assertThat(imported.getName()).isEqualTo("请假审批");
        assertThat(imported.getVersion()).isEqualTo(1);
        assertThat(imported.getNodes()).hasSize(5);
        assertThat(imported.getStartNodeId()).isEqualTo("start");

        // 节点类型
        assertThat(imported.getNode("apply").getType().name()).isEqualTo("USER_TASK");
        assertThat(imported.getNode("manager").getCandidate().getStrategy())
                .isEqualTo(CandidateStrategy.ANY);
        assertThat(imported.getNode("manager").getCandidate().getUserIds())
                .containsExactlyInAnyOrder("managerA", "managerB");

        // 连线
        assertThat(imported.getOutgoing("apply")).hasSize(1);
        assertThat(imported.getOutgoing("apply").get(0).getTo()).isEqualTo("manager");
    }

    @Test
    @DisplayName("并行网关：导出再导入应保留 fork/join 结构")
    void parallelGateway() {
        ProcessDefinition original = ProcessBuilder.create("parallel-demo")
                .start("start")
                .parallelGateway("fork")
                .userTask("task1", "任务1", Candidate.ofAny("u1"))
                .userTask("task2", "任务2", Candidate.ofAny("u2"))
                .parallelGateway("join")
                .end("end")
                .connect("start", "fork")
                .connect("fork", "task1")
                .connect("fork", "task2")
                .connect("task1", "join")
                .connect("task2", "join")
                .connect("join", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("fork").getType().name()).isEqualTo("PARALLEL_GATEWAY");
        assertThat(imported.getNode("join").getType().name()).isEqualTo("PARALLEL_GATEWAY");
        assertThat(imported.getOutgoing("fork")).hasSize(2);
        assertThat(imported.getOutgoing("fork").stream().map(t -> t.getTo()))
                .containsExactlyInAnyOrder("task1", "task2");
    }

    @Test
    @DisplayName("排他网关带条件：导出再导入应保留条件表达式")
    void exclusiveGatewayWithCondition() {
        ProcessDefinition original = ProcessBuilder.create("condition-demo")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("employee"))
                .exclusiveGateway("gw")
                .userTask("approve", "批准", Candidate.ofAny("manager"))
                .userTask("reject", "拒绝", Candidate.ofAny("manager"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gw")
                .connect("gw", "approve", "amount < 1000")
                .connect("gw", "reject", "amount >= 1000")
                .connect("approve", "end")
                .connect("reject", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("gw").getType().name()).isEqualTo("EXCLUSIVE_GATEWAY");
        assertThat(imported.getOutgoing("gw")).hasSize(2);

        var approveFlow = imported.getOutgoing("gw").stream()
                .filter(t -> t.getTo().equals("approve"))
                .findFirst()
                .orElseThrow();
        assertThat(approveFlow.getCondition()).isEqualTo("amount < 1000");

        var rejectFlow = imported.getOutgoing("gw").stream()
                .filter(t -> t.getTo().equals("reject"))
                .findFirst()
                .orElseThrow();
        assertThat(rejectFlow.getCondition()).isEqualTo("amount >= 1000");
    }

    @Test
    @DisplayName("会签（ALL 策略）：导出再导入应保留多实例标记")
    void allSignStrategy() {
        ProcessDefinition original = ProcessBuilder.create("all-sign")
                .start("start")
                .userTask("review", "会签", Candidate.ofAll("u1", "u2", "u3"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("review").getCandidate().getStrategy())
                .isEqualTo(CandidateStrategy.ALL);
        assertThat(imported.getNode("review").getCandidate().getUserIds())
                .containsExactlyInAnyOrder("u1", "u2", "u3");
    }

    @Test
    @DisplayName("超时配置：导出再导入应保留")
    void timeoutConfig() {
        ProcessDefinition original = ProcessBuilder.create("timeout-demo")
                .start("start")
                .userTask("review", "审批", Candidate.ofAny("u1"))
                .timeout("review", 3600_000, TimeoutPolicy.AUTO_REJECT)
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("review").hasTimeout()).isTrue();
        assertThat(imported.getNode("review").getTimeoutMillis()).isEqualTo(3600_000);
        assertThat(imported.getNode("review").getTimeoutPolicy()).isEqualTo(TimeoutPolicy.AUTO_REJECT);
    }

    @Test
    @DisplayName("子流程引用：导出再导入应保留 calledElement")
    void subProcessReference() {
        ProcessDefinition original = ProcessBuilder.create("main-flow")
                .start("start")
                .subProcess("sub", "子流程", "sub-process-key")
                .end("end")
                .connect("start", "sub")
                .connect("sub", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("sub").getType().name()).isEqualTo("SUB_PROCESS");
        assertThat(imported.getNode("sub").getSubProcessKey()).isEqualTo("sub-process-key");
    }

    @Test
    @DisplayName("无效 XML 应抛 BpmnException")
    void invalidXml() {
        assertThatThrownBy(() -> BpmnImporter.importFrom("<not-xml>"))
                .isInstanceOf(BpmnException.class)
                .hasMessageContaining("解析 BPMN 失败");
    }

    @Test
    @DisplayName("缺少 process 元素应抛 BpmnException")
    void missingProcess() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                </definitions>
                """;
        assertThatThrownBy(() -> BpmnImporter.importFrom(xml))
                .isInstanceOf(BpmnException.class)
                .hasMessageContaining("未找到 <process>");
    }

    @Test
    @DisplayName("userTask 无任何审批人来源应抛 BpmnException，并列出支持的形式")
    void missingCandidate() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="test" name="Test">
                    <startEvent id="start"/>
                    <userTask id="task1" name="Task 1"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="task1"/>
                    <sequenceFlow id="f2" sourceRef="task1" targetRef="end"/>
                  </process>
                </definitions>
                """;
        assertThatThrownBy(() -> BpmnImporter.importFrom(xml))
                .isInstanceOf(BpmnException.class)
                .hasMessageContaining("没有任何可用的审批人定义")
                .hasMessageContaining("candidateGroups");
    }

    @Test
    @DisplayName("sequenceFlow 缺少 sourceRef 应抛 BpmnException")
    void missingSourceRef() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="test" name="Test">
                    <startEvent id="start"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" targetRef="end"/>
                  </process>
                </definitions>
                """;
        assertThatThrownBy(() -> BpmnImporter.importFrom(xml))
                .isInstanceOf(BpmnException.class)
                .hasMessageContaining("缺少 sourceRef");
    }

    @Test
    @DisplayName("callActivity 缺少 calledElement 应抛 BpmnException")
    void missingCalledElement() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <process id="test" name="Test">
                    <startEvent id="start"/>
                    <callActivity id="sub" name="Sub"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="sub"/>
                    <sequenceFlow id="f2" sourceRef="sub" targetRef="end"/>
                  </process>
                </definitions>
                """;
        assertThatThrownBy(() -> BpmnImporter.importFrom(xml))
                .isInstanceOf(BpmnException.class)
                .hasMessageContaining("缺少 calledElement");
    }

    @Test
    @DisplayName("导入后走 ProcessBuilder.build() 校验，能捕获语义错误")
    void buildValidationCatchesSemanticErrors() {
        // 构造一个 userTask 无出口的 XML（语义错误）
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:wf="https://workflow.engine/bpmn">
                  <process id="test" name="Test">
                    <startEvent id="start"/>
                    <userTask id="task1" name="Task 1">
                      <extensionElements>
                        <wf:candidate strategy="ANY">u1</wf:candidate>
                      </extensionElements>
                    </userTask>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="task1"/>
                  </process>
                </definitions>
                """;
        // ProcessBuilder.build() 会校验所有 USER_TASK 节点都有出口
        assertThatThrownBy(() -> BpmnImporter.importFrom(xml))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有任何出口");
    }

    @Test
    @DisplayName("版本号从 wf:version 属性读取")
    void versionAttribute() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:wf="https://workflow.engine/bpmn">
                  <process id="test" name="Test" wf:version="42">
                    <startEvent id="start"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="end"/>
                  </process>
                </definitions>
                """;
        ProcessDefinition imported = BpmnImporter.importFrom(xml);
        assertThat(imported.getVersion()).isEqualTo(42);
    }

    @Test
    @DisplayName("多流程定义导出：definitions 下可有多个 process")
    void multipleProcesses() {
        ProcessDefinition def1 = ProcessBuilder.create("flow1")
                .start("s1").end("e1").connect("s1", "e1").build();
        ProcessDefinition def2 = ProcessBuilder.create("flow2")
                .start("s2").end("e2").connect("s2", "e2").build();

        String xml = BpmnExporter.export(java.util.List.of(def1, def2));
        assertThat(xml).contains("<process id=\"flow1\"");
        assertThat(xml).contains("<process id=\"flow2\"");
    }

    // ========== 事件网关往返测试 ==========

    @Test
    @DisplayName("消息事件：导出再导入应保留 messageName 和 correlationKey")
    void messageEventRoundTrip() {
        ProcessDefinition original = ProcessBuilder.create("msg-flow")
                .start("start")
                .messageEvent("waitForApproval", "等待审批", "approvalMessage", "${orderId}")
                .end("end")
                .connect("start", "waitForApproval")
                .connect("waitForApproval", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("waitForApproval").getType().name()).isEqualTo("MESSAGE_EVENT");
        assertThat(imported.getNode("waitForApproval").getMessageEvent()).isNotNull();
        assertThat(imported.getNode("waitForApproval").getMessageEvent().messageName()).isEqualTo("approvalMessage");
        assertThat(imported.getNode("waitForApproval").getMessageEvent().correlationKeyExpression()).isEqualTo("${orderId}");
    }

    @Test
    @DisplayName("信号事件：导出再导入应保留 signalName")
    void signalEventRoundTrip() {
        ProcessDefinition original = ProcessBuilder.create("sig-flow")
                .start("start")
                .signalEvent("waitForSignal", "等待信号", "systemShutdown")
                .end("end")
                .connect("start", "waitForSignal")
                .connect("waitForSignal", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("waitForSignal").getType().name()).isEqualTo("SIGNAL_EVENT");
        assertThat(imported.getNode("waitForSignal").getSignalEvent()).isNotNull();
        assertThat(imported.getNode("waitForSignal").getSignalEvent().signalName()).isEqualTo("systemShutdown");
    }

    @Test
    @DisplayName("定时器边界事件：导出再导入应保留 attachedTo / duration / interrupting")
    void timerBoundaryRoundTrip() {
        ProcessDefinition original = ProcessBuilder.create("timer-flow")
                .start("start")
                .userTask("review", "审核", Candidate.ofAny("alice"))
                .timerBoundary("timeout", "超时", "review", 60000, true)
                .end("end")
                .connect("start", "review")
                .connect("review", "timeout")
                .connect("timeout", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("timeout").getType().name()).isEqualTo("TIMER_BOUNDARY");
        assertThat(imported.getNode("timeout").getTimerBoundaryEvent()).isNotNull();
        assertThat(imported.getNode("timeout").getTimerBoundaryEvent().attachedToNodeId()).isEqualTo("review");
        assertThat(imported.getNode("timeout").getTimerBoundaryEvent().durationMillis()).isEqualTo(60000);
        assertThat(imported.getNode("timeout").getTimerBoundaryEvent().interrupting()).isTrue();
    }

    @Test
    @DisplayName("定时器边界事件（非中断模式）：interrupting=false 应保留")
    void timerBoundaryNonInterruptingRoundTrip() {
        ProcessDefinition original = ProcessBuilder.create("timer-flow-2")
                .start("start")
                .userTask("review", "审核", Candidate.ofAny("alice"))
                .timerBoundary("timeout", "超时", "review", 30000, false)
                .end("end")
                .connect("start", "review")
                .connect("review", "timeout")
                .connect("timeout", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNode("timeout").getTimerBoundaryEvent().interrupting()).isFalse();
    }

    @Test
    @DisplayName("组合流程：用户任务 + 消息事件 + 信号事件混合")
    void combinedFlowWithEvents() {
        ProcessDefinition original = ProcessBuilder.create("combined-flow")
                .start("start")
                .userTask("submit", "提交申请", Candidate.ofAny("alice"))
                .messageEvent("waitForApproval", "等待审批", "approvalMessage", "${orderId}")
                .signalEvent("notifyAll", "通知全员", "processCompleted")
                .end("end")
                .connect("start", "submit")
                .connect("submit", "waitForApproval")
                .connect("waitForApproval", "notifyAll")
                .connect("notifyAll", "end")
                .build();

        String xml = BpmnExporter.export(original);
        ProcessDefinition imported = BpmnImporter.importFrom(xml);

        assertThat(imported.getNodes()).hasSize(5);
        assertThat(imported.getNode("submit").getType().name()).isEqualTo("USER_TASK");
        assertThat(imported.getNode("waitForApproval").getType().name()).isEqualTo("MESSAGE_EVENT");
        assertThat(imported.getNode("notifyAll").getType().name()).isEqualTo("SIGNAL_EVENT");
    }
}
