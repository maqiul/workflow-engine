package com.workflow.sample;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.Candidate;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;

import java.util.List;
import java.util.Map;

/**
 * 请假审批流程 Demo
 *
 * 流程图:
 *   start → apply → manager(会签/ANY: managerA, managerB) → hr → end
 *
 * 演示场景:
 *   1. 员工发起请假
 *   2. 经理 A 审批通过(或签节点一人通过即过)
 *   3. HR 审批通过,流程结束
 */
public class LeaveDemo {

    public static void main(String[] args) {
        log("===== 自研工作流引擎 - 请假审批 Demo =====");

        // 1. 定义流程
        ProcessDefinition leave = ProcessBuilder.create("leave", "请假审批")
                .start("start")
                .userTask("apply", "提交申请", Candidate.ofAny("employee"))
                .userTask("manager", "部门经理审批", Candidate.ofAny("managerA", "managerB"))
                .userTask("hr", "HR 审批", Candidate.ofAny("hr"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "manager")
                .connect("manager", "hr")
                .connect("hr", "end")
                .build();
        log("流程定义: " + leave);
        log("节点数: " + leave.getNodes().size());

        // 2. 装配引擎
        InMemoryProcessRepository procRepo = new InMemoryProcessRepository();
        InMemoryInstanceRepository instRepo = new InMemoryInstanceRepository();
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository();
        procRepo.save(leave);

        WorkflowEngine engine = new WorkflowEngine(procRepo, instRepo, taskRepo);

        // 3. 发起流程
        log("\n----- 步骤 1: 员工发起请假 -----");
        String instanceId = engine.start("leave", Map.of("days", 3, "reason", "回家探亲"));
        log("流程发起成功 instanceId=" + instanceId);
        printInstance(engine, instanceId);

        // 4. 员工提交申请
        log("\n----- 步骤 2: 员工提交申请 -----");
        String applyTaskId = firstPendingTask(engine, instanceId);
        log("待办任务: " + applyTaskId);
        engine.completeTask(applyTaskId, "employee", true);
        log("员工提交完成");
        printInstance(engine, instanceId);

        // 5. 经理 A 审批通过(或签:任一通过即可)
        log("\n----- 步骤 3: 经理 A 审批 -----");
        String managerTaskId = firstPendingTask(engine, instanceId);
        log("待办任务: " + managerTaskId);
        engine.completeTask(managerTaskId, "managerA", true);
        log("经理 A 审批完成(会签节点一人通过即过)");
        printInstance(engine, instanceId);

        // 6. HR 审批
        log("\n----- 步骤 4: HR 审批 -----");
        String hrTaskId = firstPendingTask(engine, instanceId);
        log("待办任务: " + hrTaskId);
        engine.completeTask(hrTaskId, "hr", true);
        log("HR 审批完成");
        printInstance(engine, instanceId);

        // 7. 验证结果
        log("\n===== Demo 完成 =====");
        ProcessInstance finalInst = engine.getInstance(instanceId);
        log("最终状态: " + finalInst.getStatus());
        if (finalInst.getStatus() == InstanceStatus.COMPLETED) {
            log("✅ 流程正常结束");
        } else {
            log("❌ 流程未结束,当前状态: " + finalInst.getStatus());
        }
    }

    // ========== 辅助 ==========

    private static String firstPendingTask(WorkflowEngine engine, String instanceId) {
        ProcessInstance inst = engine.getInstance(instanceId);
        List<TaskInstance> tasks = inst.getTasks();
        for (TaskInstance t : tasks) {
            if (t.getStatus() == com.workflow.enums.TaskStatus.PENDING) {
                return t.getId();
            }
        }
        throw new IllegalStateException("实例 " + instanceId + " 无 PENDING 任务");
    }

    private static void printInstance(WorkflowEngine engine, String instanceId) {
        ProcessInstance inst = engine.getInstance(instanceId);
        log("  实例状态: " + inst.getStatus());
        log("  活跃 Token: " + inst.getActiveTokens().size());
        for (TaskInstance t : inst.getTasks()) {
            log("    任务 " + t.getId().substring(0, 8)
                    + " node=" + t.getNodeId()
                    + " candidate=" + t.getCandidate()
                    + " status=" + t.getStatus());
        }
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}