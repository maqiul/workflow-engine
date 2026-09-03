package com.workflow.sample;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.definition.VariableDefinition;
import com.workflow.definition.VariableType;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TimeoutPolicy;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自研工作流引擎 - 全功能演示
 *
 * 覆盖场景：
 *  1. 串行流程（start → task → end）
 *  2. 并行网关（fork-join）
 *  3. 排他网关（条件路由）
 *  4. 会签（ANY 或签 / ALL 会签）
 *  5. 驳回（退回上一 UserTask）
 *  6. 转办（转移给其他用户）
 *  7. 终止（强制结束实例）
 *  8. 挂起/恢复
 *  9. 子流程嵌入
 *  10. 超时调度（AUTO_APPROVE / AUTO_REJECT / AUTO_TERMINATE / AUTO_TRANSFER）
 *  11. 流程变量强类型（schema 校验）
 *  12. 流程版本管理
 */
public class ComprehensiveDemo {

    public static void main(String[] args) {
        log("===== 自研工作流引擎 - 全功能演示 =====\n");

        // 1. 串行流程
        demo_serialFlow();

        // 2. 并行网关
        demo_parallelGateway();

        // 3. 排他网关（条件路由）
        demo_exclusiveGateway();

        // 4. 会签（ANY / ALL）
        demo_signStrategy();

        // 5. 驳回
        demo_reject();

        // 6. 转办
        demo_transfer();

        // 7. 终止
        demo_terminate();

        // 8. 挂起/恢复
        demo_suspendResume();

        // 9. 子流程
        demo_subProcess();

        // 10. 超时调度（演示配置，不实际等待）
        demo_timeout();

        // 11. 流程变量强类型
        demo_variableSchema();

        // 12. 流程版本管理
        demo_processVersion();

        log("\n===== 所有演示完成 =====");
    }

    // ========== 1. 串行流程 ==========
    static void demo_serialFlow() {
        log("--- 1. 串行流程 ---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("serial", "串行流程")
                .start("start")
                .userTask("task1", "任务1", Candidate.ofAny("u1"))
                .userTask("task2", "任务2", Candidate.ofAny("u2"))
                .end("end")
                .connect("start", "task1")
                .connect("task1", "task2")
                .connect("task2", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("serial", null);
        log("  发起流程 instanceId=" + instanceId);

        // 完成 task1
        String taskId1 = firstPendingTask(engine, instanceId);
        engine.completeTask(taskId1, "u1", true);
        log("  完成 task1，当前节点: " + currentNode(engine, instanceId));

        // 完成 task2
        String taskId2 = firstPendingTask(engine, instanceId);
        engine.completeTask(taskId2, "u2", true);
        log("  完成 task2，流程状态: " + engine.getInstance(instanceId).getStatus());
        log("");
    }

    // ========== 2. 并行网关 ==========
    static void demo_parallelGateway() {
        log("--- 2. 并行网关（fork-join）---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("parallel", "并行流程")
                .start("start")
                .userTask("init", "初始化", Candidate.ofAny("u1"))
                .parallelGateway("fork")
                .userTask("branchA", "分支A", Candidate.ofAny("uA"))
                .userTask("branchB", "分支B", Candidate.ofAny("uB"))
                .parallelGateway("join")
                .userTask("finish", "收尾", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "init")
                .connect("init", "fork")
                .connect("fork", "branchA")
                .connect("fork", "branchB")
                .connect("branchA", "join")
                .connect("branchB", "join")
                .connect("join", "finish")
                .connect("finish", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("parallel", null);
        log("  发起流程");

        // 完成 init
        engine.completeTask(firstPendingTask(engine, instanceId), "u1", true);
        log("  完成 init，活跃 Token 数: " + engine.getInstance(instanceId).getActiveTokens().size());

        // 完成 branchA 和 branchB（并行）
        ProcessInstance inst = engine.getInstance(instanceId);
        List<TaskInstance> tasks = inst.getTasks().stream()
                .filter(t -> t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
                .toList();
        log("  并行任务数: " + tasks.size());

        for (TaskInstance t : tasks) {
            engine.completeTask(t.getId(), t.getCandidate().getUserIds().iterator().next(), true);
        }
        log("  完成所有分支，流程状态: " + engine.getInstance(instanceId).getStatus());
        log("");
    }

    // ========== 3. 排他网关（条件路由）==========
    static void demo_exclusiveGateway() {
        log("--- 3. 排他网关（条件路由）---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("condition", "条件流程")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .exclusiveGateway("gate")
                .userTask("manager", "经理审批（<1000）", Candidate.ofAny("m1"))
                .userTask("director", "总监审批（>=1000）", Candidate.ofAny("d1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "gate")
                .connect("gate", "manager", "amount < 1000")
                .connect("gate", "director", "amount >= 1000")
                .connect("manager", "end")
                .connect("director", "end")
                .build();
        procRepo.save(def);

        // 场景 A：amount=500，走经理
        String instA = engine.start("condition", Map.of("amount", 500));
        engine.completeTask(firstPendingTask(engine, instA), "u1", true);
        log("  amount=500，当前节点: " + currentNode(engine, instA));

        // 场景 B：amount=2000，走总监
        String instB = engine.start("condition", Map.of("amount", 2000));
        engine.completeTask(firstPendingTask(engine, instB), "u1", true);
        log("  amount=2000，当前节点: " + currentNode(engine, instB));
        log("");
    }

    // ========== 4. 会签（ANY / ALL）==========
    static void demo_signStrategy() {
        log("--- 4. 会签（ANY 或签 / ALL 会签）---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        // ANY：任一通过即可
        ProcessDefinition anyDef = ProcessBuilder.create("any-sign", "或签")
                .start("start")
                .userTask("review", "或签节点", Candidate.ofAny("u1", "u2", "u3"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();
        procRepo.save(anyDef);

        String anyInst = engine.start("any-sign", null);
        engine.completeTask(firstPendingTask(engine, anyInst), "u1", true);
        log("  ANY 或签：u1 通过，流程状态: " + engine.getInstance(anyInst).getStatus());

        // ALL：全员通过
        ProcessDefinition allDef = ProcessBuilder.create("all-sign", "会签")
                .start("start")
                .userTask("review", "会签节点", Candidate.ofAll("u1", "u2", "u3"))
                .end("end")
                .connect("start", "review")
                .connect("review", "end")
                .build();
        procRepo.save(allDef);

        String allInst = engine.start("all-sign", null);
        ProcessInstance inst = engine.getInstance(allInst);
        List<TaskInstance> tasks = inst.getTasks().stream()
                .filter(t -> t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
                .toList();
        log("  ALL 会签：待办任务数 " + tasks.size());

        for (TaskInstance t : tasks) {
            engine.completeTask(t.getId(), t.getCandidate().getUserIds().iterator().next(), true);
        }
        log("  ALL 会签：全员通过，流程状态: " + engine.getInstance(allInst).getStatus());
        log("");
    }

    // ========== 5. 驳回 ==========
    static void demo_reject() {
        log("--- 5. 驳回 ---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("reject-flow", "驳回流程")
                .start("start")
                .userTask("apply", "申请", Candidate.ofAny("u1"))
                .userTask("review", "审批", Candidate.ofAny("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("reject-flow", null);
        engine.completeTask(firstPendingTask(engine, instanceId), "u1", true);
        log("  完成 apply，当前节点: " + currentNode(engine, instanceId));

        // 驳回
        String reviewTaskId = firstPendingTask(engine, instanceId);
        engine.rejectTask(reviewTaskId, "u2", "资料不全");
        log("  驳回 review，回退到: " + currentNode(engine, instanceId));
        log("");
    }

    // ========== 6. 转办 ==========
    static void demo_transfer() {
        log("--- 6. 转办 ---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("transfer-flow", "转办流程")
                .start("start")
                .userTask("task", "任务", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "task")
                .connect("task", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("transfer-flow", null);
        String taskId = firstPendingTask(engine, instanceId);
        log("  原任务候选人: " + engine.getTask(taskId).getCandidate());

        engine.transferTask(taskId, "u1", "u2");
        log("  转办后，新任务候选人: " + engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
                .findFirst().get().getCandidate());
        log("");
    }

    // ========== 7. 终止 ==========
    static void demo_terminate() {
        log("--- 7. 终止 ---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("terminate-flow", "终止流程")
                .start("start")
                .userTask("task", "任务", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "task")
                .connect("task", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("terminate-flow", null);
        log("  发起流程，状态: " + engine.getInstance(instanceId).getStatus());

        engine.terminate(instanceId);
        log("  终止后，状态: " + engine.getInstance(instanceId).getStatus());
        log("");
    }

    // ========== 8. 挂起/恢复 ==========
    static void demo_suspendResume() {
        log("--- 8. 挂起/恢复 ---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("suspend-flow", "挂起流程")
                .start("start")
                .userTask("task", "任务", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "task")
                .connect("task", "end")
                .build();
        procRepo.save(def);

        String instanceId = engine.start("suspend-flow", null);
        log("  发起流程，状态: " + engine.getInstance(instanceId).getStatus());

        engine.suspend(instanceId);
        log("  挂起后，状态: " + engine.getInstance(instanceId).getStatus());

        engine.resume(instanceId);
        log("  恢复后，状态: " + engine.getInstance(instanceId).getStatus());
        log("");
    }

    // ========== 9. 子流程 ==========
    static void demo_subProcess() {
        log("--- 9. 子流程嵌入 ---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        // 子流程定义
        ProcessDefinition subDef = ProcessBuilder.create("sub-process", "子流程")
                .start("sub-start")
                .userTask("sub-task", "子任务", Candidate.ofAny("sub-user"))
                .end("sub-end")
                .connect("sub-start", "sub-task")
                .connect("sub-task", "sub-end")
                .build();
        procRepo.save(subDef);

        // 主流程定义
        ProcessDefinition mainDef = ProcessBuilder.create("main-process", "主流程")
                .start("start")
                .userTask("main-task", "主任务", Candidate.ofAny("main-user"))
                .subProcess("sub", "子流程节点", "sub-process")
                .end("end")
                .connect("start", "main-task")
                .connect("main-task", "sub")
                .connect("sub", "end")
                .build();
        procRepo.save(mainDef);

        String mainInstId = engine.start("main-process", null);
        engine.completeTask(firstPendingTask(engine, mainInstId), "main-user", true);
        log("  主流程完成 main-task，进入子流程");

        // 子流程实例 ID 存储在变量 __sub_<tokenId>
        ProcessInstance mainInst = engine.getInstance(mainInstId);
        String subInstId = (String) mainInst.getVariables().entrySet().stream()
                .filter(e -> e.getKey().startsWith("__sub_") && !e.getKey().equals("__sub_depth"))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
        log("  子流程实例 ID: " + subInstId);
        log("  子流程状态: " + engine.getInstance(subInstId).getStatus());
        log("");
    }

    // ========== 10. 超时调度 ==========
    static void demo_timeout() {
        log("--- 10. 超时调度（演示配置）---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("timeout-flow", "超时流程")
                .start("start")
                .userTask("task", "任务", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "task")
                .connect("task", "end")
                .timeout("task", 3600000, TimeoutPolicy.AUTO_APPROVE)  // 1小时超时自动通过
                .build();
        procRepo.save(def);

        log("  配置超时策略: 3600000ms (1小时) 后 AUTO_APPROVE");
        log("  支持策略: AUTO_APPROVE / AUTO_REJECT / AUTO_TERMINATE / AUTO_TRANSFER");
        log("");
    }

    // ========== 11. 流程变量强类型 ==========
    static void demo_variableSchema() {
        log("--- 11. 流程变量强类型 ---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        ProcessDefinition def = ProcessBuilder.create("var-flow", "变量流程")
                .start("start")
                .userTask("task", "任务", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "task")
                .connect("task", "end")
                .variable("amount", VariableType.INTEGER)
                .variable(VariableDefinition.builder("reason", VariableType.STRING)
                        .required(true)
                        .description("申请事由")
                        .build())
                .variable(VariableDefinition.builder("priority", VariableType.STRING)
                        .defaultValue("normal")
                        .build())
                .build();
        procRepo.save(def);

        // 正确传参
        Map<String, Object> vars = new HashMap<>();
        vars.put("amount", 1000);
        vars.put("reason", "出差");
        String instanceId = engine.start("var-flow", vars);
        log("  正确传参，流程状态: " + engine.getInstance(instanceId).getStatus());

        // 错误传参（类型不匹配）
        try {
            Map<String, Object> badVars = new HashMap<>();
            badVars.put("amount", "not-a-number");
            badVars.put("reason", "test");
            engine.start("var-flow", badVars);
        } catch (IllegalArgumentException e) {
            log("  类型不匹配，报错: " + e.getMessage());
        }

        // 缺少必填字段
        try {
            Map<String, Object> missingVars = new HashMap<>();
            missingVars.put("amount", 100);
            engine.start("var-flow", missingVars);
        } catch (IllegalArgumentException e) {
            log("  缺少必填字段，报错: " + e.getMessage());
        }
        log("");
    }

    // ========== 12. 流程版本管理 ==========
    static void demo_processVersion() {
        log("--- 12. 流程版本管理 ---");
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        WorkflowEngine engine = newEngine(procRepo);

        // v1
        ProcessDefinition v1 = ProcessBuilder.create("versioned", "版本流程 v1")
                .version(1)
                .start("start")
                .userTask("task-v1", "v1 任务", Candidate.ofAny("u1"))
                .end("end")
                .connect("start", "task-v1")
                .connect("task-v1", "end")
                .build();
        procRepo.save(v1);

        // v2
        ProcessDefinition v2 = ProcessBuilder.create("versioned", "版本流程 v2")
                .version(2)
                .start("start")
                .userTask("task-v2", "v2 任务", Candidate.ofAny("u2"))
                .end("end")
                .connect("start", "task-v2")
                .connect("task-v2", "end")
                .build();
        procRepo.save(v2);

        // 启动 v1 实例
        String instV1 = engine.start("versioned", 1, null);
        log("  启动 v1 实例，节点: " + currentNode(engine, instV1));

        // 启动 v2 实例
        String instV2 = engine.start("versioned", 2, null);
        log("  启动 v2 实例，节点: " + currentNode(engine, instV2));

        // 默认启动最新版
        String instLatest = engine.start("versioned", null);
        log("  默认启动最新版，节点: " + currentNode(engine, instLatest));
        log("");
    }

    // ========== 辅助方法 ==========
    static WorkflowEngine newEngine(InMemoryProcessRepository procRepo) {
        return new WorkflowEngine(procRepo, new InMemoryInstanceRepository(), new InMemoryTaskRepository());
    }

    static String firstPendingTask(WorkflowEngine engine, String instanceId) {
        return engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getStatus() == com.workflow.enums.TaskStatus.PENDING)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("无 PENDING 任务"))
                .getId();
    }

    static String currentNode(WorkflowEngine engine, String instanceId) {
        ProcessInstance inst = engine.getInstance(instanceId);
        return inst.getActiveTokens().values().stream()
                .map(t -> t.getCurrentNodeId())
                .findFirst()
                .orElse("N/A");
    }

    static void log(String msg) {
        System.out.println(msg);
    }
}
