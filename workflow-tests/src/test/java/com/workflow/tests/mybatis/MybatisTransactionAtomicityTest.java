package com.workflow.tests.mybatis;

import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.MybatisEngineTestBase;
import com.workflow.tests.support.ExplodingAuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MyBatis-Plus 路径的事务原子性 —— 与 {@code JpaTransactionAtomicityTest} 同一组断言。
 *
 * <p>刻意用两套独立仓储实现跑同一探针：事务边界属于<b>引擎</b>的正确性，
 * 只在 InMemory 上修好等于没修。哪一套仓储漏了共享事务，这里就会红。
 */
@DisplayName("MyBatis 事务原子性")
class MybatisTransactionAtomicityTest extends MybatisEngineTestBase {

    private void registerFlow(String key) {
        register(simple(key)
                .start("start")
                .userTask("review", "审批", any("u1"))
                .userTask("hr", "人事", any("u3"))
                .end("end")
                .connect("start", "review")
                .connect("review", "hr")
                .connect("hr", "end")
                .build());
    }

    @Test
    @DisplayName("审计写入失败时，任务状态与 Token 位置必须像什么都没发生")
    void completeTaskShouldRollBackWhenAuditFails() {
        registerFlow("mb-tx");
        String instanceId = engine.start("mb-tx", Map.of());
        String taskId = engine.getInstance(instanceId).getTasks().get(0).getId();

        WorkflowEngine explodingEngine = new WorkflowEngine(
                procRepo, instRepo, taskRepo, null, new ExplodingAuditLogRepository());

        assertThatThrownBy(() -> explodingEngine.completeTask(taskId, "u1", true))
                .as("故障应当向调用方抛出")
                .isInstanceOf(IllegalStateException.class);
        explodingEngine.shutdown();

        TaskInstance task = taskRepo.findById(taskId);
        assertThat(task.getStatus())
                .as("MyBatis 路径必须整体回滚，不能留下已 COMPLETED 的半完成任务")
                .isEqualTo(TaskStatus.PENDING);

        ProcessInstance inst = instRepo.findById(instanceId);
        assertThat(inst.getStatus()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(inst.getActiveTokens()).hasSize(1);
        assertThat(inst.getActiveTokens().values().iterator().next().getCurrentNodeId())
                .as("Token 不应停留在已提交的下一节点")
                .isEqualTo("review");
        assertThat(taskRepo.findByInstanceId(instanceId))
                .as("下游 hr 任务不应残留")
                .filteredOn(t -> "hr".equals(t.getNodeId()))
                .isEmpty();
    }

    @Test
    @DisplayName("驳回中途失败时，REJECTED 状态与回退 Token 必须一起撤销")
    void rejectTaskShouldRollBackAtomically() {
        register(simple("mb-tx-rej")
                .start("start")
                .userTask("apply", "申请", any("u0"))
                .userTask("review", "审批", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build());

        String instanceId = engine.start("mb-tx-rej", Map.of());
        engine.completeTask(engine.getInstance(instanceId).getTasks().get(0).getId(), "u0", true);
        String reviewTask = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> "review".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow().getId();

        WorkflowEngine explodingEngine = new WorkflowEngine(
                procRepo, instRepo, taskRepo, null, new ExplodingAuditLogRepository());

        assertThatThrownBy(() -> explodingEngine.rejectTask(reviewTask, "u1", "资料不全"))
                .isInstanceOf(IllegalStateException.class);
        explodingEngine.shutdown();

        assertThat(taskRepo.findById(reviewTask).getStatus())
                .as("驳回失败则任务不应停在 REJECTED")
                .isEqualTo(TaskStatus.PENDING);
        ProcessInstance inst = instRepo.findById(instanceId);
        assertThat(inst.getActiveTokens()).hasSize(1);
        assertThat(inst.getActiveTokens().values().iterator().next().getCurrentNodeId())
                .as("Token 不应被回退到 apply（驳回并未生效）")
                .isEqualTo("review");
    }

    @Test
    @DisplayName("并行网关一次动作内的多表写入彼此可见")
    void writesAcrossRepositoriesAreVisibleWithinOneAction() {
        register(simple("mb-visible")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .parallelGateway("fork")
                .userTask("r1", "评审1", any("u2"))
                .userTask("r2", "评审2", any("u3"))
                .parallelGateway("join")
                .end("end")
                .connect("start", "apply")
                .connect("apply", "fork")
                .connect("fork", "r1")
                .connect("fork", "r2")
                .connect("r1", "join")
                .connect("r2", "join")
                .connect("join", "end")
                .build());

        String instanceId = engine.start("mb-visible", Map.of());
        engine.completeTask(engine.getInstance(instanceId).getTasks().get(0).getId(), "u1", true);

        ProcessInstance inst = instRepo.findById(instanceId);
        assertThat(inst.getActiveTokens()).as("fork 应产生两条活跃 Token").hasSize(2);
        assertThat(taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING))
                .as("两条分支各生成一个待办")
                .hasSize(2);

        engine.completeTask(taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "r1".equals(t.getNodeId())).findFirst().orElseThrow().getId(), "u2", true);
        assertThat(instRepo.findById(instanceId).getStatus())
                .as("仅一条分支完成，实例必须仍在等待")
                .isEqualTo(InstanceStatus.RUNNING);

        engine.completeTask(taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> "r2".equals(t.getNodeId())).findFirst().orElseThrow().getId(), "u3", true);
        assertThat(instRepo.findById(instanceId).getStatus())
                .as("两分支汇合后应完成")
                .isEqualTo(InstanceStatus.COMPLETED);
    }
}
