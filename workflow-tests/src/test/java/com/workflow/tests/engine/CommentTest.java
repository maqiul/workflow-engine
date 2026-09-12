package com.workflow.tests.engine;

import com.workflow.builder.ProcessBuilder;
import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngine;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.CommentType;
import com.workflow.enums.TaskStatus;
import com.workflow.history.HistoryRetention;
import com.workflow.repository.InMemoryCommentRepository;
import com.workflow.repository.InMemoryHistoryRepository;
import com.workflow.runtime.Comment;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.EngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审批意见测试 —— OA 集成缺口表 #1。
 *
 * <p>核心断言只有一条：<b>意见不随历史保留策略消失</b>。
 * 其余的（类型、顺序、节点推导、快速失败）都是围绕这条保证的边界。
 */
class CommentTest extends EngineTestBase {

    private InMemoryCommentRepository commentRepo;
    private InMemoryHistoryRepository historyRepo;

    @BeforeEach
    void init() {
        super.setUp();
        commentRepo = new InMemoryCommentRepository();
        historyRepo = new InMemoryHistoryRepository();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .auditLogRepository(auditLogRepo)
                .commentRepository(commentRepo)
                .historyRepository(historyRepo)
                .build();
    }

    private void twoStep() {
        ProcessDefinition def = ProcessBuilder.create("comment-flow", "意见测试")
                .start("start")
                .userTask("apply", "申请", any("u1"))
                .userTask("approve", "审批", any("u2"))
                .end("end")
                .connect("start", "apply")
                .connect("apply", "approve")
                .connect("approve", "end")
                .build();
        register(def);
    }

    private TaskInstance taskOf(String instanceId, String nodeId) {
        return engine.getInstance(instanceId).getTasks().stream()
                .filter(t -> t.getNodeId().equals(nodeId))
                .findFirst().orElseThrow(() -> new AssertionError("节点无待办: " + nodeId));
    }

    @Test
    @DisplayName("意见落库，节点从任务推导，调用方不必传")
    void comment_should_be_recorded_and_derive_node_from_task() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());
        TaskInstance apply = taskOf(instanceId, "apply");

        Comment comment = engine.addComment(instanceId, apply.getId(), "u1", "出差三天，请批准");

        assertThat(comment.getType()).isEqualTo(CommentType.COMMENT);
        assertThat(comment.getNodeId()).isEqualTo("apply");
        assertThat(comment.getTaskId()).isEqualTo(apply.getId());
        assertThat(comment.hasMessage()).isTrue();
        assertThat(engine.getTaskComments(apply.getId())).hasSize(1);
        assertThat(engine.getInstanceComments(instanceId)).hasSize(1);
    }

    @Test
    @DisplayName("流程级意见：不挂任务的说明（如发起人附言）")
    void process_level_comment_should_be_allowed() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());

        Comment comment = engine.addComment(instanceId, null, "u1", "整体说明：涉及跨部门");

        assertThat(comment.getTaskId()).isNull();
        assertThat(comment.getNodeId()).isNull();
        assertThat(engine.getInstanceComments(instanceId)).hasSize(1);
        assertThat(engine.getTaskComments("any")).isEmpty();
    }

    @Test
    @DisplayName("驳回理由进一等存储 —— 不再只落在会被清理的审计日志里")
    void reject_reason_should_be_stored_as_comment() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());
        engine.completeTask(taskOf(instanceId, "apply").getId(), "u1", true);
        TaskInstance approve = taskOf(instanceId, "approve");

        engine.rejectTask(approve.getId(), "u2", "金额超标，请重新申请");

        List<Comment> comments = engine.getTaskComments(approve.getId());
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getType()).isEqualTo(CommentType.REJECT);
        assertThat(comments.get(0).getMessage()).isEqualTo("金额超标，请重新申请");
        assertThat(comments.get(0).getUserId()).isEqualTo("u2");
    }

    @Test
    @DisplayName("完成带意见：同一次事务里写 APPROVE 意见")
    void complete_with_comment_should_store_approve_comment() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());
        TaskInstance apply = taskOf(instanceId, "apply");

        engine.completeTask(apply.getId(), "u1", true, "同意，请继续");

        List<Comment> comments = engine.getTaskComments(apply.getId());
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getType()).isEqualTo(CommentType.APPROVE);
        assertThat(comments.get(0).getMessage()).isEqualTo("同意，请继续");
    }

    @Test
    @DisplayName("完成不带意见时不写空意见 —— 空记录会污染导出")
    void complete_without_comment_should_not_write_comment() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());
        TaskInstance apply = taskOf(instanceId, "apply");

        engine.completeTask(apply.getId(), "u1", true);
        assertThat(engine.getTaskComments(apply.getId())).isEmpty();

        engine.completeTask(taskOf(instanceId, "approve").getId(), "u2", true, "   ");
        assertThat(engine.getInstanceComments(instanceId)).isEmpty();
    }

    @Test
    @DisplayName("同毫秒多条按写入顺序 —— 顺序不能交给随机 UUID 决定")
    void comments_should_keep_chronological_order() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());

        engine.addComment(instanceId, null, "u1", "第一条");
        engine.addComment(instanceId, null, "u1", "第二条");
        engine.addComment(instanceId, null, "u2", "第三条");

        assertThat(engine.getInstanceComments(instanceId))
                .extracting(Comment::getMessage)
                .containsExactly("第一条", "第二条", "第三条");
    }

    @Test
    @DisplayName("★ 保留策略清空历史后，意见一条不少")
    void comments_should_survive_history_retention_purge() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());
        TaskInstance apply = taskOf(instanceId, "apply");
        engine.completeTask(apply.getId(), "u1", true, "同意，资料齐全");

        // 前提：历史确实写了，否则这条测试是空转
        assertThat(historyRepo.findTasksByInstanceId(instanceId)).isNotEmpty();

        // 把保留策略开到「删光一切已落定历史」
        HistoryRetention.CleanupResult purged =
                HistoryRetention.purgeBefore(historyRepo, System.currentTimeMillis() + 1000);

        // 历史被清空……
        assertThat(purged.total()).isGreaterThan(0);
        assertThat(historyRepo.findTasksByInstanceId(instanceId)).isEmpty();

        // ……但审批意见仍在。这正是缺口 #1 的要害：意见曾被放在会过期的地方。
        assertThat(engine.getInstanceComments(instanceId))
                .extracting(Comment::getMessage)
                .containsExactly("同意，资料齐全");
    }

    @Test
    @DisplayName("意见清理是独立开关，不跟历史保留策略联动")
    void comment_cleanup_should_be_independent_from_history() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());
        engine.addComment(instanceId, null, "u1", "要保留的说明");

        HistoryRetention.purgeBefore(historyRepo, System.currentTimeMillis() + 1000);
        assertThat(engine.getInstanceComments(instanceId)).hasSize(1);

        // 只有显式调用意见清理才会删，且删的是「早于 cutoff」的
        assertThat(commentRepo.deleteBefore(System.currentTimeMillis() + 1000)).isEqualTo(1);
        assertThat(engine.getInstanceComments(instanceId)).isEmpty();
    }

    @Test
    @DisplayName("未注入意见仓储时快速失败，不静默丢数据")
    void comments_should_fail_fast_when_repository_not_injected() {
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo);
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());

        assertThatThrownBy(() -> engine.addComment(instanceId, null, "u1", "hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CommentRepository");
        assertThatThrownBy(() -> engine.getInstanceComments(instanceId))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> engine.getTaskComments("t1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("不注入意见仓储时，审批动作照常工作（零回归）")
    void approval_actions_should_still_work_without_comment_repository() {
        engine = new WorkflowEngine(procRepo, instRepo, taskRepo);
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());

        engine.completeTask(taskOf(instanceId, "apply").getId(), "u1", true, "这条意见无处可落");
        TaskInstance approve = taskOf(instanceId, "approve");
        engine.rejectTask(approve.getId(), "u2", "重来");

        assertThat(engine.getInstance(instanceId).getTasks())
                .anyMatch(t -> t.getNodeId().equals("apply") && t.getStatus() == TaskStatus.PENDING);
    }

    @Test
    @DisplayName("实例或任务不存在时报错，不留下悬空意见")
    void add_comment_should_reject_unknown_target() {
        twoStep();
        String instanceId = engine.start("comment-flow", Map.of());
        TaskInstance apply = taskOf(instanceId, "apply");

        assertThatThrownBy(() -> engine.addComment("no-such-instance", null, "u1", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("流程实例不存在");
        assertThatThrownBy(() -> engine.addComment(instanceId, "no-such-task", "u1", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务不存在");

        assertThat(engine.getInstanceComments(instanceId)).isEmpty();
        assertThat(engine.getTaskComments(apply.getId())).isEmpty();
    }
}
