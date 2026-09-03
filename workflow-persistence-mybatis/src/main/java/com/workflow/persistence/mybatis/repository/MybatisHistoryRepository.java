package com.workflow.persistence.mybatis.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfHistActivityEntity;
import com.workflow.persistence.mybatis.entity.WfHistTaskEntity;
import com.workflow.persistence.mybatis.mapper.WfHistActivityMapper;
import com.workflow.persistence.mybatis.mapper.WfHistTaskMapper;
import com.workflow.repository.HistoryRepository;
import com.workflow.runtime.HistoricActivityInstance;
import org.apache.ibatis.session.SqlSession;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * MyBatis-Plus 版历史活动仓储。
 *
 * <p>列表查询一律走 {@code BaseMapper} + {@code QueryWrapper} 而非手写 SQL：
 * 自动全列映射，避开「新增列忘了写进 SELECT、读回时字段静默丢失」这个本项目的老坑。
 *
 * <p>事务由 {@link MybatisPersistence#inSession} 保证与业务写入共享同一 SqlSession，
 * 因此历史行与实例/任务行同生同灭。
 */
public class MybatisHistoryRepository implements HistoryRepository {

    private final MybatisPersistence mb;

    public MybatisHistoryRepository(MybatisPersistence mb) {
        this.mb = Objects.requireNonNull(mb);
    }

    @Override
    public void save(HistoricActivityInstance a) {
        Objects.requireNonNull(a);
        mb.inSession(session -> {
            WfHistActivityMapper mapper = session.getMapper(WfHistActivityMapper.class);
            WfHistActivityEntity e = mapper.selectById(a.getId());
            boolean isNew = (e == null);
            if (isNew) {
                e = new WfHistActivityEntity();
                e.setId(a.getId());
            }
            e.setInstanceId(a.getInstanceId());
            e.setProcessKey(a.getProcessKey());
            e.setProcessVersion(a.getProcessVersion());
            e.setActivityId(a.getActivityId());
            e.setActivityType(a.getActivityType());
            e.setTokenId(a.getTokenId());
            e.setTaskId(a.getTaskId());
            e.setStartTime(a.getStartTime());
            e.setSeq(a.getSeq());
            e.setEndTime(a.getEndTime());
            e.setPerformer(a.getPerformer());
            if (isNew) {
                mapper.insert(e);
            } else {
                mapper.updateById(e);
            }
            return null;
        });
    }

    @Override
    public HistoricActivityInstance findOpen(String instanceId, String tokenId, String activityId) {
        return mb.inSession(session -> session.getMapper(WfHistActivityMapper.class)
                .selectList(new QueryWrapper<WfHistActivityEntity>()
                        .eq("instance_id", instanceId)
                        .eq("token_id", tokenId)
                        .eq("activity_id", activityId)
                        .isNull("end_time")
                        .last("LIMIT 1"))
                .stream().findFirst()
                .map(MybatisHistoryRepository::toDomain)
                .orElse(null));
    }

    @Override
    public List<HistoricActivityInstance> findByInstanceId(String instanceId) {
        return query(qw -> qw.eq("instance_id", instanceId));
    }

    @Override
    public List<HistoricActivityInstance> findByActivity(String processKey, String activityId) {
        return query(qw -> qw.eq("process_key", processKey).eq("activity_id", activityId));
    }

    @Override
    public OptionalDouble averageClosedDuration(String processKey, String activityId) {
        return mb.inSession(session -> {
            Double avg = session.getMapper(WfHistActivityMapper.class)
                    .avgClosedDuration(processKey, activityId);
            return avg == null ? OptionalDouble.empty() : OptionalDouble.of(avg);
        });
    }

    @Override
    public int deleteClosedBefore(long cutoffMillis) {
        return mb.inSession(session -> session.getMapper(WfHistActivityMapper.class)
                .delete(new QueryWrapper<WfHistActivityEntity>()
                        .isNotNull("end_time")
                        .lt("start_time", cutoffMillis)));
    }

    private List<HistoricActivityInstance> query(
            java.util.function.Consumer<QueryWrapper<WfHistActivityEntity>> filter) {
        return mb.inSession(session -> {
            QueryWrapper<WfHistActivityEntity> qw = new QueryWrapper<>();
            filter.accept(qw);
            // startTime 只到毫秒，同毫秒内必须靠 seq 定序，否则报表路径顺序随机
            qw.orderByAsc("start_time", "seq");
            return session.getMapper(WfHistActivityMapper.class)
                    .selectList(qw).stream()
                    .map(MybatisHistoryRepository::toDomain)
                    .toList();
        });
    }

    /** 实体转独立 domain 对象，仓库内部状态不外泄。 */
    private static HistoricActivityInstance toDomain(WfHistActivityEntity e) {
        return HistoricActivityInstance.reconstruct(
                e.getId(), e.getInstanceId(), e.getProcessKey(), e.getProcessVersion(),
                e.getActivityId(), e.getActivityType(), e.getTokenId(), e.getTaskId(),
                e.getStartTime(), e.getEndTime(), e.getPerformer(), e.getSeq());
    }

    // ========== 历史任务 ==========

    @Override
    public void saveTask(com.workflow.runtime.HistoricTaskInstance t) {
        Objects.requireNonNull(t);
        mb.inSession(session -> {
            WfHistTaskMapper mapper = session.getMapper(WfHistTaskMapper.class);
            WfHistTaskEntity e = mapper.selectById(t.getTaskId());
            boolean isNew = (e == null);
            if (isNew) {
                e = new WfHistTaskEntity();
                e.setTaskId(t.getTaskId());
            }
            e.setInstanceId(t.getInstanceId());
            e.setProcessKey(t.getProcessKey());
            e.setProcessVersion(t.getProcessVersion());
            e.setNodeId(t.getNodeId());
            e.setCandidateUsers(toCsv(t.getCandidateUsers()));
            e.setCompletedBy(toCsv(t.getCompletedBy()));
            e.setStartTime(t.getStartTime());
            e.setEndTime(t.getEndTime());
            e.setSeq(t.getSeq());
            e.setEndReason(t.getEndReason());
            if (isNew) {
                mapper.insert(e);
            } else {
                mapper.updateById(e);
            }
            return null;
        });
    }

    @Override
    public List<com.workflow.runtime.HistoricTaskInstance> findTasksByInstanceId(String instanceId) {
        return mb.inSession(session -> session.getMapper(WfHistTaskMapper.class)
                .selectList(new QueryWrapper<WfHistTaskEntity>()
                        .eq("instance_id", instanceId)
                        .orderByAsc("end_time", "seq"))
                .stream().map(MybatisHistoryRepository::toTaskDomain).toList());
    }

    @Override
    public List<com.workflow.runtime.HistoricTaskInstance> findTasksInvolving(String userId) {
        // 逗号包裹存储让 like 天然生成 '%,u1,%'，不会把 u1 误配到 u11
        String token = "," + userId + ",";
        return mb.inSession(session -> session.getMapper(WfHistTaskMapper.class)
                .selectList(new QueryWrapper<WfHistTaskEntity>()
                        .like("candidate_users", token).or().like("completed_by", token)
                        .orderByAsc("end_time", "seq"))
                .stream().map(MybatisHistoryRepository::toTaskDomain).toList());
    }

    @Override
    public OptionalDouble averageClosedTaskDuration(String processKey, String nodeId) {
        return mb.inSession(session -> {
            Double avg = session.getMapper(WfHistTaskMapper.class)
                    .avgDuration(processKey, nodeId);
            return avg == null ? OptionalDouble.empty() : OptionalDouble.of(avg);
        });
    }

    @Override
    public int deleteTasksBefore(long cutoffMillis) {
        return mb.inSession(session -> session.getMapper(WfHistTaskMapper.class)
                .delete(new QueryWrapper<WfHistTaskEntity>().lt("end_time", cutoffMillis)));
    }

    /** 人员列表以 {@code ,u1,u2,} 形式落库，便于 LIKE 精确匹配。 */
    private static String toCsv(java.util.Collection<String> users) {
        if (users == null || users.isEmpty()) {
            return ",";
        }
        return "," + String.join(",", users) + ",";
    }

    private static List<String> fromCsv(String csv) {
        if (csv == null || csv.length() <= 1) {
            return List.of();
        }
        String body = csv;
        if (body.startsWith(",")) body = body.substring(1);
        if (body.endsWith(",")) body = body.substring(0, body.length() - 1);
        if (body.isEmpty()) return List.of();
        return java.util.Arrays.stream(body.split(",")).filter(s -> !s.isEmpty()).toList();
    }

    private static com.workflow.runtime.HistoricTaskInstance toTaskDomain(WfHistTaskEntity e) {
        return com.workflow.runtime.HistoricTaskInstance.reconstruct(
                e.getTaskId(), e.getInstanceId(), e.getProcessKey(), e.getProcessVersion(),
                e.getNodeId(), fromCsv(e.getCandidateUsers()), fromCsv(e.getCompletedBy()),
                e.getStartTime(), e.getEndTime(), e.getEndReason(), e.getSeq());
    }

    /** 便于按实例清理（测试隔离用）。 */
    public int deleteByInstanceId(String instanceId) {
        return mb.inSession(session -> session.getMapper(WfHistActivityMapper.class)
                .delete(new QueryWrapper<WfHistActivityEntity>().eq("instance_id", instanceId)));
    }
}
