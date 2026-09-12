package com.workflow.tests.jpa;

import com.workflow.definition.ProcessDefinition;
import com.workflow.engine.WorkflowEngineBuilder;
import com.workflow.enums.CommentType;
import com.workflow.history.HistoryRetention;
import com.workflow.repository.CommentRepository;
import com.workflow.repository.HistoryRepository;
import com.workflow.runtime.Comment;
import com.workflow.runtime.TaskInstance;
import com.workflow.tests.JpaEngineTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 审批意见测试（JPA 版）—— 同一套语义在真库仓储下必须成立。
 *
 * <p>同时验证 {@code V10__comment.sql} 真的被执行到了：本类刻意不做任何建表动作，
 * 表不存在这些用例就会整批失败，而不是静默通过。
 */
class JpaCommentTest extends JpaEngineTestBase {

    private CommentRepository commentRepo;
    private HistoryRepository historyRepo;

    @BeforeEach
    void initCommentSupport() {
        commentRepo = jpa.commentRepo();
        historyRepo = jpa.historyRepo();
        engine = WorkflowEngineBuilder.builder(procRepo, instRepo, taskRepo)
                .auditLogRepository(auditLogRepo)
                .commentRepository(commentRepo)
                .historyRepository(historyRepo)
                .build();
        // wf_comment 与 wf_hist_* 不在基类 clearTables 的清单里（它们是后加的能力），
        // 这里自己清，保证用例之间互不影响。
        jpa.inTransaction(em -> {
            em.createNativeQuery("DELETE FROM wf_comment").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_hist_task").executeUpdate();
            em.createNativeQuery("DELETE FROM wf_hist_activity").executeUpdate();
            return null;
        });
    }

    private void twoStep() {
        ProcessDefinition def = simple("jpa-comment")
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
        return taskRepo.findByInstanceId(instanceId).stream()
                .filter(t -> t.getNodeId().equals(nodeId))
                .findFirst().orElseThrow(() -> new AssertionError("节点无待办: " + nodeId));
    }

    @Test
    @DisplayName("意见经真库往返：字段一个不少")
    void comment_should_round_trip_through_jpa() {
        twoStep();
        String instanceId = engine.start("jpa-comment", null);
        TaskInstance apply = taskOf(instanceId, "apply");

        engine.addComment(instanceId, apply.getId(), "u1", "出差三天，请批准");

        List<Comment> comments = engine.getInstanceComments(instanceId);
        assertThat(comments).hasSize(1);
        Comment c = comments.get(0);
        assertThat(c.getInstanceId()).isEqualTo(instanceId);
        assertThat(c.getTaskId()).isEqualTo(apply.getId());
        assertThat(c.getNodeId()).isEqualTo("apply");
        assertThat(c.getUserId()).isEqualTo("u1");
        assertThat(c.getType()).isEqualTo(CommentType.COMMENT);
        assertThat(c.getMessage()).isEqualTo("出差三天，请批准");
        assertThat(c.getCreateTime()).isGreaterThan(0);
    }

    @Test
    @DisplayName("流程级意见与任务级意见同表共存，按时间升序")
    void instance_comments_should_merge_process_and_task_level() {
        twoStep();
        String instanceId = engine.start("jpa-comment", null);
        TaskInstance apply = taskOf(instanceId, "apply");

        engine.addComment(instanceId, null, "u1", "流程级：涉及跨部门");
        engine.addComment(instanceId, apply.getId(), "u1", "任务级：附件已补");
        engine.addComment(instanceId, null, "u2", "流程级：已阅");

        assertThat(engine.getInstanceComments(instanceId))
                .extracting(Comment::getMessage)
                .containsExactly("流程级：涉及跨部门", "任务级：附件已补", "流程级：已阅");
        assertThat(engine.getTaskComments(apply.getId())).hasSize(1);
    }

    @Test
    @DisplayName("驳回理由落库并能按任务取回")
    void reject_reason_should_be_persisted() {
        twoStep();
        String instanceId = engine.start("jpa-comment", null);
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
    @DisplayName("完成带意见：同事务写 APPROVE 意见")
    void complete_with_comment_should_persist() {
        twoStep();
        String instanceId = engine.start("jpa-comment", null);
        TaskInstance apply = taskOf(instanceId, "apply");

        engine.completeTask(apply.getId(), "u1", true, "同意，请继续");

        assertThat(engine.getTaskComments(apply.getId()))
                .extracting(Comment::getType, Comment::getMessage)
                .containsExactly(tuple(CommentType.APPROVE, "同意，请继续"));
    }

    @Test
    @DisplayName("★ 保留策略清空历史后，意见在真库中仍在")
    void comments_should_survive_history_retention_purge() {
        twoStep();
        String instanceId = engine.start("jpa-comment", null);
        engine.completeTask(taskOf(instanceId, "apply").getId(), "u1", true, "同意，资料齐全");

        assertThat(historyRepo.findTasksByInstanceId(instanceId)).isNotEmpty();

        HistoryRetention.CleanupResult purged =
                HistoryRetention.purgeBefore(historyRepo, System.currentTimeMillis() + 1000);

        assertThat(purged.total()).isGreaterThan(0);
        assertThat(historyRepo.findTasksByInstanceId(instanceId)).isEmpty();
        assertThat(engine.getInstanceComments(instanceId))
                .extracting(Comment::getMessage)
                .containsExactly("同意，资料齐全");
    }

    @Test
    @DisplayName("按人查意见：最近在前")
    void find_by_user_should_return_latest_first() {
        twoStep();
        String instanceId = engine.start("jpa-comment", null);

        engine.addComment(instanceId, null, "u1", "较早");
        engine.addComment(instanceId, null, "u1", "较晚");
        engine.addComment(instanceId, null, "u2", "别人的");

        assertThat(commentRepo.findByUser("u1"))
                .extracting(Comment::getMessage)
                .containsExactly("较晚", "较早");
    }

    @Test
    @DisplayName("显式清理：只删早于 cutoff 的意见")
    void delete_before_should_remove_old_comments() {
        twoStep();
        String instanceId = engine.start("jpa-comment", null);
        engine.addComment(instanceId, null, "u1", "第一条");
        engine.addComment(instanceId, null, "u1", "第二条");

        assertThat(commentRepo.deleteBefore(System.currentTimeMillis() + 1000)).isEqualTo(2);
        assertThat(engine.getInstanceComments(instanceId)).isEmpty();

        // cutoff 早于全部记录时不该删掉任何一条
        engine.addComment(instanceId, null, "u1", "第三条");
        assertThat(commentRepo.deleteBefore(0L)).isZero();
        assertThat(engine.getInstanceComments(instanceId)).hasSize(1);
    }

    @Test
    @DisplayName("意见正文可为空：「同意」这类动作本身即信息")
    void message_may_be_null() {
        twoStep();
        String instanceId = engine.start("jpa-comment", null);

        engine.addComment(instanceId, null, "u1", CommentType.APPROVE, null);

        List<Comment> comments = engine.getInstanceComments(instanceId);
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).getMessage()).isNull();
        assertThat(comments.get(0).hasMessage()).isFalse();
        assertThat(comments.get(0).getType()).isEqualTo(CommentType.APPROVE);
    }
}
