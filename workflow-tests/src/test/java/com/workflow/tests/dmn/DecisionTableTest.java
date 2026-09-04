package com.workflow.tests.dmn;

import com.workflow.builder.ProcessBuilder;
import com.workflow.dmn.DecisionTable;
import com.workflow.dmn.DecisionTableExecutor;
import com.workflow.dmn.InMemoryDecisionRepository;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DMN 决策表测试
 * 
 * 测试场景：
 * - 决策表定义和执行
 * - 不同命中策略
 * - 流程集成
 */
@DisplayName("DMN 决策表")
class DecisionTableTest {

    private InMemoryProcessRepository procRepo;
    private InMemoryInstanceRepository instRepo;
    private InMemoryTaskRepository taskRepo;
    private InMemoryDecisionRepository decisionRepo;
    private DecisionTableExecutor decisionExecutor;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        decisionRepo = new InMemoryDecisionRepository();
        decisionExecutor = new DecisionTableExecutor();

        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .decisionRepository(decisionRepo)
                .build();
    }

    @Test
    @DisplayName("决策表执行：FIRST 命中策略")
    void decisionTableExecute_firstHitPolicy() {
        // 创建决策表：输入 amount，输出 approvalLevel
        DecisionTable table = new DecisionTable(
                "approval-table",
                "审批级别决策",
                Arrays.asList(new DecisionTable.InputClause("amount", "amount")),
                Arrays.asList(new DecisionTable.OutputClause("approvalLevel", "string")),
                Arrays.asList(
                        new DecisionTable.DecisionRule(
                                "rule1",
                                Arrays.asList("${_input} < 1000"),
                                Arrays.asList("\"manager\""),
                                1
                        ),
                        new DecisionTable.DecisionRule(
                                "rule2",
                                Arrays.asList("${_input} >= 1000 && ${_input} < 5000"),
                                Arrays.asList("\"director\""),
                                2
                        ),
                        new DecisionTable.DecisionRule(
                                "rule3",
                                Arrays.asList("${_input} >= 5000"),
                                Arrays.asList("\"ceo\""),
                                3
                        )
                ),
                DecisionTable.HitPolicy.FIRST
        );

        decisionRepo.save(table);

        // 测试不同金额
        Map<String, Object> context1 = new HashMap<>();
        context1.put("amount", 500);
        DecisionTableExecutor.DecisionResult result1 = decisionExecutor.execute(table, context1);
        assertThat(result1.isMatched()).isTrue();
        assertThat(result1.getSingleOutput()).containsEntry("approvalLevel", "manager");

        Map<String, Object> context2 = new HashMap<>();
        context2.put("amount", 3000);
        DecisionTableExecutor.DecisionResult result2 = decisionExecutor.execute(table, context2);
        assertThat(result2.isMatched()).isTrue();
        assertThat(result2.getSingleOutput()).containsEntry("approvalLevel", "director");

        Map<String, Object> context3 = new HashMap<>();
        context3.put("amount", 10000);
        DecisionTableExecutor.DecisionResult result3 = decisionExecutor.execute(table, context3);
        assertThat(result3.isMatched()).isTrue();
        assertThat(result3.getSingleOutput()).containsEntry("approvalLevel", "ceo");
    }

    @Test
    @DisplayName("决策表执行：通配符匹配")
    void decisionTableExecute_wildcard() {
        DecisionTable table = new DecisionTable(
                "user-type-table",
                "用户类型决策",
                Arrays.asList(new DecisionTable.InputClause("userType", "userType")),
                Arrays.asList(new DecisionTable.OutputClause("discount", "number")),
                Arrays.asList(
                        new DecisionTable.DecisionRule(
                                "rule-vip",
                                Arrays.asList("vip"),
                                Arrays.asList("0.2"),
                                1
                        ),
                        new DecisionTable.DecisionRule(
                                "rule-all",
                                Arrays.asList("-"),  // 通配符
                                Arrays.asList("0.1"),
                                2
                        )
                ),
                DecisionTable.HitPolicy.FIRST
        );

        decisionRepo.save(table);

        // VIP 用户
        Map<String, Object> vipContext = new HashMap<>();
        vipContext.put("userType", "vip");
        DecisionTableExecutor.DecisionResult vipResult = decisionExecutor.execute(table, vipContext);
        assertThat(vipResult.isMatched()).isTrue();
        assertThat(vipResult.getSingleOutput()).containsEntry("discount", 0.2);

        // 普通用户（通配符匹配）
        Map<String, Object> normalContext = new HashMap<>();
        normalContext.put("userType", "normal");
        DecisionTableExecutor.DecisionResult normalResult = decisionExecutor.execute(table, normalContext);
        assertThat(normalResult.isMatched()).isTrue();
        assertThat(normalResult.getSingleOutput()).containsEntry("discount", 0.1);
    }

    @Test
    @DisplayName("决策表执行：无匹配规则")
    void decisionTableExecute_noMatch() {
        DecisionTable table = new DecisionTable(
                "test-table",
                "测试表",
                Arrays.asList(new DecisionTable.InputClause("status", "status")),
                Arrays.asList(new DecisionTable.OutputClause("result", "string")),
                Arrays.asList(
                        new DecisionTable.DecisionRule(
                                "rule1",
                                Arrays.asList("approved"),
                                Arrays.asList("\"ok\""),
                                1
                        )
                ),
                DecisionTable.HitPolicy.FIRST
        );

        decisionRepo.save(table);

        Map<String, Object> context = new HashMap<>();
        context.put("status", "unknown");
        DecisionTableExecutor.DecisionResult result = decisionExecutor.execute(table, context);
        assertThat(result.isMatched()).isFalse();
    }

    @Test
    @DisplayName("流程集成：决策节点选择审批级别")
    void decisionNodeInFlow_selectsApprovalLevel() {
        // 创建决策表
        DecisionTable table = new DecisionTable(
                "approval-table",
                "审批级别决策",
                Arrays.asList(new DecisionTable.InputClause("amount", "amount")),
                Arrays.asList(new DecisionTable.OutputClause("approvalLevel", "string")),
                Arrays.asList(
                        new DecisionTable.DecisionRule(
                                "rule1",
                                Arrays.asList("${_input} < 1000"),
                                Arrays.asList("\"manager\""),
                                1
                        ),
                        new DecisionTable.DecisionRule(
                                "rule2",
                                Arrays.asList("${_input} >= 1000"),
                                Arrays.asList("\"director\""),
                                2
                        )
                ),
                DecisionTable.HitPolicy.FIRST
        );
        decisionRepo.save(table);

        // 创建流程：start -> apply -> decision -> manager/director -> end
        ProcessDefinition def = ProcessBuilder.create("approval-flow", "审批流程")
                .start("start")
                .userTask("apply", "提交申请", Candidate.ofAny("user1"))
                .decision("decision", "审批级别决策", "approval-table")
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .userTask("director", "总监审批", Candidate.ofAny("director1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "decision")
                .connect("decision", "manager", "${variables.approvalLevel == 'manager'}")
                .connect("decision", "director", "${variables.approvalLevel == 'director'}")
                .connect("manager", "end")
                .connect("director", "end")
                .build();
        procRepo.save(def);

        // 启动流程（小金额，应该走经理审批）
        String instanceId = engine.start("approval-flow", Map.of("amount", 500));
        ProcessInstance instance = engine.getInstance(instanceId);
        
        // 验证：流程在运行中
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        
        // 验证：决策结果已写入变量
        assertThat(instance.getVariable("approvalLevel")).isEqualTo("manager");
        
        // 验证：当前任务应该是 manager
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).isNotEmpty();
        TaskInstance currentTask = tasks.stream()
                .filter(t -> t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
                .findFirst()
                .orElseThrow();
        assertThat(currentTask.getNodeId()).isEqualTo("manager");
    }
}