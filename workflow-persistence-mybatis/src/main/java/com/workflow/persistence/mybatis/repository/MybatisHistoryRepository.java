package com.workflow.persistence.mybatis.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.workflow.persistence.mybatis.MybatisPersistence;
import com.workflow.persistence.mybatis.entity.WfHistActivityEntity;
import com.workflow.persistence.mybatis.mapper.WfHistActivityMapper;
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

    /** 便于按实例清理（测试隔离用）。 */
    public int deleteByInstanceId(String instanceId) {
        return mb.inSession(session -> session.getMapper(WfHistActivityMapper.class)
                .delete(new QueryWrapper<WfHistActivityEntity>().eq("instance_id", instanceId)));
    }
}
