package com.workflow.tests.jpa;

import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.enums.AuditEventType;
import com.workflow.enums.InstanceStatus;
import com.workflow.enums.TaskStatus;
import com.workflow.repository.AuditLogRepository;
import com.workflow.runtime.AuditLog;
import com.workflow.runtime.ProcessInstance;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JPA 路径的事务原子性 —— 与 InMemory 版 B3 同一缺陷，换到真实数据库上复现。
 *
 * <p>InMemory 的修复靠 undo log，但那是内存实现专用的。JPA 仓储此前每个都自己
 * {@code inTransaction} 开短事务，一次 {@code completeTask} 跨 instance / task / token /
 * audit 的写入是<b>四次独立提交</b>：中途失败，前几次已经落库的数据不会撤销。
 *
 * <p>本用例因此<b>在 v3.6 语义下应当失败</b>，用于钉死「修了内存 ≠ 修了引擎」。
 */
@DisplayName("JPA 事务原子性")
class JpaTransactionAtomicityTest extends JpaEngineTestBase {

    /** 写审计日志时抛异常的装饰仓储，模拟「前几步已提交、最后一步失败」。 */
    private AuditLogRepository explodingAudit() {
        return new AuditLogRepository() {
            @Override
            public void save(AuditLog log) {
                if (log.getEventType() == AuditEventType.TASK_COMPLETED) {
                    throw new IllegalStateException("模拟审计库故障");
                }
            }
            @Override
            public List<AuditLog> findByInstanceId(String instanceId) { return List.of(); }
            @Override
            public List<AuditLog> findByTaskId(String taskId) { return List.of(); }
            @Override
            public List<AuditLog> findByTimeRange(Instant from, Instant to) { return List.of(); }
            @Override
            public void clear() { }
        };
    }

    private void registerFlow() {
        register(simple("jpa-tx")
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
        registerFlow();

        // 先用正常引擎发起流程（独立动作，本就该提交）
        String instanceId = engine.start("jpa-tx", Map.of());
        String taskId = engine.getInstance(instanceId).getTasks().get(0).getId();

        // 换成审计会爆炸的引擎实例执行完成动作
        WorkflowEngine explodingEngine = new WorkflowEngine(
                procRepo, instRepo, taskRepo, null, explodingAudit());

        assertThatThrownBy(() -> explodingEngine.completeTask(taskId, "u1", true))
                .as("故障应当向调用方抛出")
                .isInstanceOf(IllegalStateException.class);
        explodingEngine.shutdown();

        // 核心断言：数据库里必须回到调用前的样子
        TaskInstance task = taskRepo.findById(taskId);
        assertThat(task.getStatus())
                .as("JPA 路径必须整体回滚，不能留下已 COMPLETED 的半完成任务")
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
        register(simple("jpa-tx-rej")
                .start("start")
                .userTask("apply", "申请", any("u0"))
                .userTask("review", "审批", any("u1"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "review")
                .connect("review", "end")
                .build());

        String instanceId = engine.start("jpa-tx-rej", Map.of());
        engine.completeTask(engine.getInstance(instanceId).getTasks().get(0).getId(), "u0", true);
        String reviewTask = engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> "review".equals(t.getNodeId()) && t.getStatus() == TaskStatus.PENDING)
                .findFirst().orElseThrow().getId();

        // 让驳回的审计写入失败：任务状态、Token 消耗、新 Token 创建必须一起回滚
        AuditLogRepository exploding = new AuditLogRepository() {
            @Override
            public void save(AuditLog log) {
                if (log.getEventType() == AuditEventType.TASK_REJECTED) {
                    throw new IllegalStateException("模拟审计库故障");
                }
            }
            @Override
            public List<AuditLog> findByInstanceId(String instanceId) { return List.of(); }
            @Override
            public List<AuditLog> findByTaskId(String taskId) { return List.of(); }
            @Override
            public List<AuditLog> findByTimeRange(Instant from, Instant to) { return List.of(); }
            @Override
            public void clear() { }
        };
        WorkflowEngine explodingEngine = new WorkflowEngine(
                procRepo, instRepo, taskRepo, null, exploding);

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
    @DisplayName("同一次动作内跨仓储写入彼此可见（不各自另起事务）")
    void writesAcrossRepositoriesAreVisibleWithinOneAction() {
        // 并行网关会在一次动作里：消耗 token、新建 token、新建 task、更新 instance。
        // 若各仓储另起事务，Hibernate 一级缓存 + 未 flush 的原生查询会读到旧相。
        register(simple("jpa-visible")
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

        String instanceId = engine.start("jpa-visible", Map.of());
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
