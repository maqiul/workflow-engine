package com.workflow.repository;

import com.workflow.runtime.Comment;

import java.util.List;

/**
 * 审批意见仓储。
 *
 * <p><b>本接口刻意全部使用抽象方法，一个 {@code default} 都没有</b> ——
 * 与 {@link HistoryRepository} 同一个理由：{@code default} 抛
 * {@code UnsupportedOperationException} 当扩展手段，会让内存版全实现、真库全漏，
 * 而测试全绿、编译器不报错。缺失实现必须让编译失败。
 *
 * <p><b>与历史保留策略的关系</b>：意见<b>不随</b> {@code HistoryRetention.purgeBefore} 清理。
 * 保留策略删的是活动/任务等过程数据，意见是审批证据，归档导出要求长期保留。
 * 因此清理入口是独立的 {@link #deleteBefore(long)}，由调用方显式决定何时用。
 *
 * <p>实现方须遵守两条契约：
 * <ul>
 *   <li><b>拷贝语义</b>：{@code save} 存入副本、查询返回副本，仓库持有的对象不外泄；</li>
 *   <li><b>事务内写入</b>：意见必须与产生它的业务写入同一事务提交，
 *       否则业务回滚后库里会留下「说过但从没说过」的幽灵意见。</li>
 * </ul>
 */
public interface CommentRepository {

    /** 保存（按 id 幂等覆盖）一条意见。 */
    void save(Comment comment);

    /**
     * 某实例下的全部意见，按 {@code createTime} 升序、同毫秒按 {@code seq} 升序。
     *
     * <p>升序而非倒序是刻意的：审批意见的价值在「谁先说了什么」，
     * 调用方要倒序展示自己 reverse，比让所有调用方各自纠正顺序可靠。
     */
    List<Comment> findByInstanceId(String instanceId);

    /** 某张待办上的全部意见，排序同上。 */
    List<Comment> findByTaskId(String taskId);

    /** 某人发表过的全部意见（跨实例），按 {@code createTime} 降序（最近在前）。 */
    List<Comment> findByUser(String userId);

    /**
     * 删除创建时间早于 {@code cutoffMillis} 的意见，返回删除条数。
     *
     * <p><b>刻意不接进 {@code HistoryRetention}</b>：它是个独立开关。
     * 一旦默认接进保留策略，「归档要长期保留意见」这条需求会被静默破坏。
     */
    int deleteBefore(long cutoffMillis);
}
