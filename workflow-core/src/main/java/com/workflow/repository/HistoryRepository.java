package com.workflow.repository;

import com.workflow.runtime.HistoricActivityInstance;

import java.util.List;
import java.util.OptionalDouble;

/**
 * 历史活动仓储。
 *
 * <p><b>本接口刻意全部使用抽象方法，一个 {@code default} 都没有。</b>
 * 此前 {@code InstanceRepository} / {@code TaskRepository} 用
 * 「{@code default} 抛 {@code UnsupportedOperationException}」当扩展手段，
 * 结果内存版全实现、JPA 与 MyBatis 全漏，「历史查询」在真实库上一调用即炸，
 * 而测试全绿、编译器也不报错。缺失实现必须让编译失败，故此处强制表态。
 *
 * <p>实现方须遵守两条契约：
 * <ul>
 *   <li><b>拷贝语义</b>：{@code save} 存入副本、查询返回副本，仓库持有的对象不外泄；</li>
 *   <li><b>事务内写入</b>：历史行必须与产生它的业务写入同一事务提交，
 *       否则业务回滚后库里会留下"发生过但从没发生"的幽灵活动。</li>
 * </ul>
 */
public interface HistoryRepository {

    /** 保存（新增或覆盖）一条历史活动。 */
    void save(HistoricActivityInstance activity);

    /**
     * 查找尚未闭合的活动，用于把 UserTask 的"开"与"闭"接上 —— 两者分属两次引擎推进。
     *
     * @return 没有则返回 null
     */
    HistoricActivityInstance findOpen(String instanceId, String tokenId, String activityId);

    /** 某实例的全部历史活动，按开始时间升序。 */
    List<HistoricActivityInstance> findByInstanceId(String instanceId);

    /**
     * 跨实例按「流程 key + 节点」取活动 —— 效能分析的基本切片。
     * 冗余的 processKey 列让这条查询不需要 join 实例表。
     */
    List<HistoricActivityInstance> findByActivity(String processKey, String activityId);

    /**
     * 已闭合活动的平均耗时（毫秒）。
     *
     * <p>未闭合的活动<b>不计入</b>：它们的"耗时"会随查询时刻漂移，
     * 混进平均值会让报表在刷新时自己变化。无样本时返回 {@link OptionalDouble#empty()}。
     */
    OptionalDouble averageClosedDuration(String processKey, String activityId);

    /**
     * 删除开始时间早于 {@code cutoffMillis} 的<b>已闭合</b>活动，返回删除条数。
     *
     * <p>未闭合活动一律保留 —— 删掉进行中的活动会让实例永久留下一个闭合不了的洞。
     */
    int deleteClosedBefore(long cutoffMillis);
}
