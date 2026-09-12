package com.workflow.persistence.mybatis.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfCommentEntity;
import com.workflow.persistence.mybatis.mapper.WfCommentMapper;
import com.workflow.repository.CommentRepository;
import com.workflow.runtime.Comment;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * MyBatis-Plus 版审批意见仓储。
 *
 * <p>列表查询走 {@code BaseMapper} + {@code QueryWrapper} 而非手写 SQL：自动全列映射，
 * 避开「新增列忘了写进 SELECT、读回时字段静默丢失」这个本项目的老坑。
 *
 * <p>事务由 {@link MybatisPersistence#inSession} 保证与业务写入共享同一 SqlSession，
 * 因此意见与实例/任务行同生同灭 —— 业务回滚不会留下「说过但从没说过」的幽灵意见。
 *
 * <p>{@link #deleteBefore} <b>刻意不接进</b> {@code HistoryRetention}：
 * 意见是归档导出的一等证据，清理必须是调用方显式的决定。
 */
public class MybatisCommentRepository implements CommentRepository {

    private final MybatisPersistence mb;

    public MybatisCommentRepository(MybatisPersistence mb) {
        this.mb = Objects.requireNonNull(mb);
    }

    @Override
    public void save(Comment comment) {
        Objects.requireNonNull(comment);
        mb.inSession(session -> {
            WfCommentMapper mapper = session.getMapper(WfCommentMapper.class);
            WfCommentEntity e = mapper.selectById(comment.getId());
            boolean isNew = (e == null);
            if (isNew) {
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
            if (isNew) {
                mapper.insert(e);
            } else {
                mapper.updateById(e);
            }
            return null;
        });
    }

    @Override
    public List<Comment> findByInstanceId(String instanceId) {
        return query(qw -> qw.eq("instance_id", instanceId));
    }

    @Override
    public List<Comment> findByTaskId(String taskId) {
        return query(qw -> qw.eq("task_id", taskId));
    }

    @Override
    public List<Comment> findByUser(String userId) {
        return mb.inSession(session -> session.getMapper(WfCommentMapper.class)
                .selectList(new QueryWrapper<WfCommentEntity>()
                        .eq("user_id", userId)
                        .orderByDesc("create_time", "seq"))
                .stream().map(MybatisCommentRepository::toDomain).toList());
    }

    @Override
    public int deleteBefore(long cutoffMillis) {
        return mb.inSession(session -> session.getMapper(WfCommentMapper.class)
                .delete(new QueryWrapper<WfCommentEntity>().lt("create_time", cutoffMillis)));
    }

    /** 升序查询：时间戳只到毫秒，同毫秒内必须靠 seq 定序，否则审批链顺序随机。 */
    private List<Comment> query(Consumer<QueryWrapper<WfCommentEntity>> filter) {
        return mb.inSession(session -> {
            QueryWrapper<WfCommentEntity> qw = new QueryWrapper<>();
            filter.accept(qw);
            qw.orderByAsc("create_time", "seq");
            return session.getMapper(WfCommentMapper.class)
                    .selectList(qw).stream()
                    .map(MybatisCommentRepository::toDomain)
                    .toList();
        });
    }

    /** 实体转独立 domain 对象，仓库内部状态不外泄。 */
    private static Comment toDomain(WfCommentEntity e) {
        return Comment.reconstruct(e.getId(), e.getInstanceId(), e.getTaskId(), e.getNodeId(),
                e.getUserId(), e.getType(), e.getMessage(), e.getCreateTime(), e.getSeq());
    }
}
