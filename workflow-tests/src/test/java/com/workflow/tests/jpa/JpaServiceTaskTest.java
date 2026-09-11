package com.workflow.tests.jpa;

import com.workflow.definition.ProcessDefinition;
import com.workflow.delegate.DelegateExecution;
import com.workflow.delegate.ServiceTaskDelegate;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA 服务任务节点测试
 */
@DisplayName("JPA 服务任务节点")
class JpaServiceTaskTest extends JpaEngineTestBase {

    @Test
    @DisplayName("JPA: 正常执行 delegate 后自动推进")
    void normalExecution() {
        AtomicInteger counter = new AtomicInteger(0);
        
        // 注册 delegate
        engine.registerDelegate("increment", (DelegateExecution execution) -> {
            counter.incrementAndGet();
        });

        ProcessDefinition def = simple("jpa-service-task")
                .start("start")
                .userTask("apply", "申请", any("user1"))
                .serviceTask("notify", "发送通知", "increment")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "notify")
                .connect("notify", "end")
                .build();
        register(def);

        String instanceId = engine.start("jpa-service-task", Map.of());

        // 完成 apply 任务
        List<TaskInstance> tasks = taskRepo.findByInstanceId(instanceId);
        assertThat(tasks).hasSize(1);
        TaskInstance applyTask = tasks.get(0);
        assertThat(applyTask.getNodeId()).isEqualTo("apply");
        assertThat(applyTask.getStatus()).isEqualTo(TaskStatus.PENDING);

        engine.completeTask(applyTask.getId(), "user1", true);

        // 验证 delegate 被执行
        assertThat(counter.get()).isEqualTo(1);

        // 验证流程完成
        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    @DisplayName("JPA: delegate 可以访问流程变量")
    void delegateCanAccessVariables() {
        engine.registerDelegate("check-var", (DelegateExecution execution) -> {
            Object value = execution.getVariable("testVar");
            if (!"hello".equals(value)) {
                throw new RuntimeException("变量值不对");
            }
        });

        ProcessDefinition def = simple("jpa-service-task-var")
                .start("start")
                .serviceTask("check", "检查变量", "check-var")
                .end("end")
                .connect("start", "check")
                .connect("check", "end")
                .build();
        register(def);

        String instanceId = engine.start("jpa-service-task-var", Map.of("testVar", "hello"));

        ProcessInstance instance = instRepo.findById(instanceId);
        assertThat(instance.getStatus()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
