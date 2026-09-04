package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.InstanceStatus;
import com.workflow.repository.InMemoryCarbonCopyRepository;
import com.workflow.repository.InMemoryInstanceRepository;
import com.workflow.repository.InMemoryProcessRepository;
import com.workflow.repository.InMemoryTaskRepository;
import com.workflow.runtime.CarbonCopy;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 抄送功能测试
 */
class CarbonCopyTest extends EngineTestBase {

    private InMemoryCarbonCopyRepository ccRepo;

    @BeforeEach
    void init() {
        super.setUp();
        ccRepo = new InMemoryCarbonCopyRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .carbonCopyRepository(ccRepo)
                .build();
    }

    @Test
    void carbon_copy_should_create_records_for_recipients() {
        // 定义流程
        ProcessDefinition def = ProcessBuilder.create("cc-flow", "抄送测试")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("approve", "审批", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        // 发起流程
        String instanceId = engine.start("cc-flow", Map.of());

        // 手动抄送给多人
        engine.carbonCopy(instanceId, null, "apply", List.of("observer1", "observer2"), "u1", "请知悉");

        // 验证抄送记录
        List<CarbonCopy> cc1 = engine.getCarbonCopies("observer1");
        List<CarbonCopy> cc2 = engine.getCarbonCopies("observer2");
        
        assertThat(cc1).hasSize(1);
        assertThat(cc2).hasSize(1);
        assertThat(cc1.get(0).getRecipient()).isEqualTo("observer1");
        assertThat(cc1.get(0).getOperator()).isEqualTo("u1");
        assertThat(cc1.get(0).getMessage()).isEqualTo("请知悉");
        assertThat(cc1.get(0).isRead()).isFalse();
    }

    @Test
    void unread_carbon_copies_should_filter_unread() {
        ProcessDefinition def = ProcessBuilder.create("cc-unread", "未读抄送")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("cc-unread", Map.of());

        // 抄送 3 条
        engine.carbonCopy(instanceId, null, "node1", List.of("observer"), "u1", "消息1");
        engine.carbonCopy(instanceId, null, "node2", List.of("observer"), "u2", "消息2");
        engine.carbonCopy(instanceId, null, "node3", List.of("observer"), "u3", "消息3");

        // 验证未读数量
        List<CarbonCopy> unread = engine.getUnreadCarbonCopies("observer");
        assertThat(unread).hasSize(3);

        // 标记 1 条已读
        engine.markCarbonCopyRead(unread.get(0).getId());

        // 验证未读数量减少
        unread = engine.getUnreadCarbonCopies("observer");
        assertThat(unread).hasSize(2);
    }

    @Test
    void carbon_copy_should_work_with_task_completion() {
        ProcessDefinition def = ProcessBuilder.create("cc-task", "任务抄送")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("approve", "审批", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "approve")
                .connect("approve", "end")
                .build();
        register(def);

        String instanceId = engine.start("cc-task", Map.of());
        
        // 完成申请任务
        TaskInstance applyTask = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("apply"))
                .findFirst().orElseThrow();
        engine.completeTask(applyTask.getId(), "u1", true);

        // 任务完成后抄送
        TaskInstance approveTask = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals("approve"))
                .findFirst().orElseThrow();
        engine.carbonCopy(instanceId, approveTask.getId(), "approve", 
                List.of("admin"), "u1", "申请已提交，请审批");

        // 验证抄送
        List<CarbonCopy> ccList = engine.getCarbonCopies("admin");
        assertThat(ccList).hasSize(1);
        assertThat(ccList.get(0).getTaskId()).isEqualTo(approveTask.getId());
        assertThat(ccList.get(0).getNodeId()).isEqualTo("approve");
    }

    @Test
    void carbon_copy_should_be_empty_when_not_enabled() {
        // 不注入 CarbonCopyRepository
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo);
        
        ProcessDefinition def = ProcessBuilder.create("no-cc", "无抄送")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "end")
                .build();
        register(def);

        String instanceId = engine.start("no-cc", Map.of());

        // 查询应该返回空
        List<CarbonCopy> ccList = engine.getCarbonCopies("observer");
        assertThat(ccList).isEmpty();
    }
}
