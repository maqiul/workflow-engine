package com.workflow.repository;

import com.workflow.enums.JobState;
import com.workflow.runtime.Job;

import java.util.List;

/**
 * 异步作业仓储 —— 作业执行器的存储底座。
 *
 * <p>三套实现（InMemory / JPA / MyBatis）必须给出<b>同一答案</b>：
 * 作业执行器跨进程重启、跨节点工作，全靠这份契约。
 *
 * <p>所有方法都是<b>抽象</b>而非 default：作业表是跨节点协调的唯一真相源，
 * 缺实现会静默变成"内存绿、真库炸"的老坑（已踩过两次）。
 */
public interface JobRepository {

    /**
     * 保存作业（新增或按 id 覆盖）。
     *
     * <p>按 id 幂等：同一 id 重复保存 = 更新。这样「超时作业被重复注册」不会产生重复行。
     */
    void save(Job job);

    /** 按 id 取作业；不存在返回 null */
    Job findById(String id);

    /**
     * 取到期且可领取的作业（按到期时间升序）。
     *
     * @param now  当前时刻
     * @param limit 最多取几条
     */
    List<Job> findDue(long now, int limit);

    /**
     * 原子领取作业 —— 多节点下保证同一作业只被一个节点执行。
     *
     * <p>实现须做到「条件满足才改」：只有当作业仍可领取（未被他人有效持有）时，
     * 才写入 {@code lockOwner} / {@code lockExpiresAt}；被他人持有时<b>不得</b>修改任何字段。
     *
     * @return true 表示领取成功（本节点持有）；false 表示被别人抢先或已不存在
     */
    boolean claim(String jobId, String owner, long now, long leaseUntil);

    /**
     * 执行成功 —— 删除该作业（终态）。
     */
    void delete(String jobId);

    /**
     * 执行失败 —— 记错误、累计重试次数、重新排期（退避后）。
     *
     * @param jobId      作业 id
     * @param error      最后一次错误信息
     * @param nextDueAt  下次可执行时刻（退避后）
     */
    void markRetry(String jobId, String error, long nextDueAt);

    /**
     * 重试耗尽 —— 置为死信（FAILED），行保留供人工排查。
     */
    void markFailed(String jobId, String error);

    /** 按状态查作业（观测用：READY=待处理，FAILED=死信） */
    List<Job> findByState(JobState state, int limit);

    /** 按实例查作业（实例结束后清理用） */
    List<Job> findByInstanceId(String instanceId);

    /** 删除某实例的全部作业，返回删除条数 */
    int deleteByInstanceId(String instanceId);

    /** 统计各状态作业数（观测用） */
    long countByState(JobState state);
}
