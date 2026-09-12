package com.workflow.persistence.jpa.repository;

import com.workflow.persistence.jpa.JpaPersistence;
import com.workflow.persistence.jpa.entity.WfCommentEntity;
import com.workflow.repository.CommentRepository;
import com.workflow.runtime.Comment;

import java.util.List;
import java.util.Objects;

/**
 * JPA 版审批意见仓储。
 *
 * <p>与其余 JPA 仓储共用 {@link JpaPersistence#inTransaction}，因此意见写入与业务写入
 * 同生同灭 —— 业务回滚不会留下「说过但从没说过」的幽灵意见。
 *
 * <p>{@link #deleteBefore} <b>刻意不接进</b> {@code HistoryRetention}：
 * 意见是归档导出的一等证据，清理必须是调用方显式的决定。
 */
public class JpaCommentRepository implements CommentRepository {

    private final JpaPersistence jpa;

    public JpaCommentRepository(JpaPersistence jpa) {
        this.jpa = Objects.requireNonNull(jpa);
    }

    @Override
    public void save(Comment comment) {
        Objects.requireNonNull(comment);
        jpa.inTransaction(em -> {
            WfCommentEntity e = em.find(WfCommentEntity.class, comment.getId());
            if (e == null) {
                e = new WfCommentEntity();
                e.setId(comment.getId());
            }
            e.setInstanceId(comment.getInstanceId());
            e.setTaskId(comment.getTaskId());
            e.setNodeId(comment.getNodeId());
            e.setUserId(comment.getUserId());
            e.setType(comment.getType());
            e.setMessage(comment.getMessage());
            e.setCreateTime(comment.getCreateTime());
            e.setSeq(comment.getSeq());
            em.merge(e);
            return null;
        });
    }

    @Override
    public List<Comment> findByInstanceId(String instanceId) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfCommentEntity e WHERE e.instanceId = :iid"
                                + " ORDER BY e.createTime, e.seq", WfCommentEntity.class)
                .setParameter("iid", instanceId)
                .getResultList().stream().map(JpaCommentRepository::toDomain).toList());
    }

    @Override
    public List<Comment> findByTaskId(String taskId) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfCommentEntity e WHERE e.taskId = :tid"
                                + " ORDER BY e.createTime, e.seq", WfCommentEntity.class)
                .setParameter("tid", taskId)
                .getResultList().stream().map(JpaCommentRepository::toDomain).toList());
    }

    @Override
    public List<Comment> findByUser(String userId) {
        return jpa.inTransaction(em -> em.createQuery(
                        "SELECT e FROM WfCommentEntity e WHERE e.userId = :uid"
                                + " ORDER BY e.createTime DESC, e.seq DESC", WfCommentEntity.class)
                .setParameter("uid", userId)
                .getResultList().stream().map(JpaCommentRepository::toDomain).toList());
    }

    @Override
    public int deleteBefore(long cutoffMillis) {
        return jpa.inTransaction(em -> em.createQuery(
                        "DELETE FROM WfCommentEntity e WHERE e.createTime < :cutoff")
                .setParameter("cutoff", cutoffMillis)
                .executeUpdate());
    }

    /** 查询结果为托管实体，一律转成独立 domain 对象，仓库内部状态不外泄。 */
    private static Comment toDomain(WfCommentEntity e) {
        return Comment.reconstruct(e.getId(), e.getInstanceId(), e.getTaskId(), e.getNodeId(),
                e.getUserId(), e.getType(), e.getMessage(), e.getCreateTime(), e.getSeq());
    }
}
