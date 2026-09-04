package com.workflow.tests.dmn;

import com.workflow.builder.ProcessBuilder;
import com.workflow.dmn.DecisionHistory;
import com.workflow.dmn.DecisionHistoryRepository;
import com.workflow.dmn.DecisionTable;
import com.workflow.dmn.DecisionTableExecutor;
import com.workflow.dmn.InMemoryDecisionHistoryRepository;
import com.workflow.dmn.InMemoryDecisionRepository;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
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
    private InMemoryDecisionHistoryRepository decisionHistoryRepo;
    private DecisionTableExecutor decisionExecutor;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        procRepo = new InMemoryProcessRepository();
        instRepo = new InMemoryInstanceRepository();
        taskRepo = new InMemoryTaskRepository();
        decisionRepo = new InMemoryDecisionRepository();
        decisionHistoryRepo = new InMemoryDecisionHistoryRepository();
        decisionExecutor = new DecisionTableExecutor();

        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .decisionRepository(decisionRepo)
                .decisionHistoryRepository(decisionHistoryRepo)
                .build();
    }

    @Test
    @DisplayName("决策表执行：FEEL 表达式评估")
    void decisionTableExecute_feelExpression() {
        // 创建决策表：输入 amount，输出 approvalLevel
        // 使用范围表达式 "1000..5000" 表示 amount >= 1000 且 amount < 5000
        DecisionTable table = new DecisionTable(
                "approval-table",
                "审批级别决策",
                Arrays.asList(new DecisionTable.InputClause("amount", "amount")),
                Arrays.asList(new DecisionTable.OutputClause("approvalLevel", "string")),
                Arrays.asList(
                        new DecisionTable.DecisionRule(
                                "rule1",
                                Arrays.asList("..1000"),
                                Arrays.asList("\"manager\""),
                                1
                        ),
                        new DecisionTable.DecisionRule(
                                "rule2",
                                Arrays.asList("1000..5000"),
                                Arrays.asList("\"director\""),
                                2
                        ),
                        new DecisionTable.DecisionRule(
                                "rule3",
                                Arrays.asList("5000.."),
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
                                Arrays.asList("-"),
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
    @DisplayName("决策表执行：FIRST 命中策略 - 第一个匹配")
    void decisionTableExecute_firstMatch() {
        DecisionTable table = new DecisionTable(
                "priority-table",
                "优先级决策",
                Arrays.asList(new DecisionTable.InputClause("score", "score")),
                Arrays.asList(new DecisionTable.OutputClause("level", "string")),
                Arrays.asList(
                        new DecisionTable.DecisionRule(
                                "rule1",
                                Arrays.asList("80.."),
                                Arrays.asList("\"A\""),
                                1
                        ),
                        new DecisionTable.DecisionRule(
                                "rule2",
                                Arrays.asList("60..80"),
                                Arrays.asList("\"B\""),
                                2
                        )
                ),
                DecisionTable.HitPolicy.FIRST
        );

        decisionRepo.save(table);

        Map<String, Object> context = new HashMap<>();
        context.put("score", 90);
        DecisionTableExecutor.DecisionResult result = decisionExecutor.execute(table, context);
        assertThat(result.isMatched()).isTrue();
        // FIRST 策略应该命中第一条规则（虽然 score 90 也满足 rule2）
        assertThat(result.getSingleOutput()).containsEntry("level", "A");
    }

    @Test
    @DisplayName("流程集成：决策节点选择审批级别")
    void decisionNodeInFlow_selectsApprovalLevel() {
        // 创建决策表：使用范围表达式
        DecisionTable table = new DecisionTable(
                "approval-table",
                "审批级别决策",
                Arrays.asList(new DecisionTable.InputClause("amount", "amount")),
                Arrays.asList(new DecisionTable.OutputClause("approvalLevel", "string")),
                Arrays.asList(
                        new DecisionTable.DecisionRule(
                                "rule1",
                                Arrays.asList("..1000"),
                                Arrays.asList("\"manager\""),
                                1
                        ),
                        new DecisionTable.DecisionRule(
                                "rule2",
                                Arrays.asList("1000.."),
                                Arrays.asList("\"director\""),
                                2
                        )
                ),
                DecisionTable.HitPolicy.FIRST
        );
        decisionRepo.save(table);

        // 创建简单流程：start -> decision -> manager -> end
        // decision 节点单出口测试
        ProcessDefinition def = ProcessBuilder.create("approval-flow", "审批流程")
                .start("start")
                .decision("decision", "审批级别决策", "approval-table")
                .userTask("manager", "经理审批", Candidate.ofAny("manager1"))
                .userTask("director", "总监审批", Candidate.ofAny("director1"))
                .end("end")
                .connect("start", "decision")
                .connect("decision", "manager")
                .connect("decision", "director")
                .connect("manager", "end")
                .connect("director", "end")
                .build();
        procRepo.save(def);

        // 启动流程（小金额，应该走 manager）
        String instanceId = engine.start("approval-flow", Map.of("amount", 500));
        ProcessInstance instance = engine.getInstance(instanceId);
        
        // 验证：流程在运行中
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        
        // 验证：决策结果已写入变量
        assertThat(instance.getVariable("approvalLevel")).isEqualTo("manager");
        
        // 验证：决策历史已保存
        List<DecisionHistory> histories = decisionHistoryRepo.findByInstanceId(instanceId);
        assertThat(histories).hasSize(1);
        DecisionHistory history = histories.get(0);
        assertThat(history.getDecisionTableId()).isEqualTo("approval-table");
        assertThat(history.getNodeId()).isEqualTo("decision");
        assertThat(history.getMatchedRuleId()).isEqualTo("rule1");
        assertThat(history.getOutputs()).containsEntry("approvalLevel", "manager");
        assertThat(history.getInputs()).containsEntry("amount", 500);
    }
}