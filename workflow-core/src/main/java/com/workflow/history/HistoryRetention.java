package com.workflow.history;

import com.workflow.repository.HistoryRepository;
import com.workflow.tx.TransactionRunner;

import java.util.Objects;

/**
 * 历史数据保留策略 —— 统一清理入口。
 *
 * <p>历史表只有写入和零散的 delete 方法，缺一个"安全清理"的收口。风险点很具体：
 * 活动表里有未闭合记录（UserTask 还在等人），
 * 若无脑按时间删，等那人终于批完，闭合动作会找不到自己那行 ——
 * 于是这条审批在报表里<b>永久消失</b>，而且不报错。
 *
 * <p>因此本类的护栏是硬的：
 * <ul>
 *   <li>活动只删 {@code end_time IS NOT NULL} 的行（未闭合的一律保留）；</li>
 *   <li>任务表本就只存已落定的记录，可安全按结束时间删；</li>
 *   <li>两类删除在<b>同一次调用</b>内完成，避免出现"活动清了、任务还在"的半截归档。</li>
 * </ul>
 */
public final class HistoryRetention {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    private HistoryRetention() { }

    /** 一次清理的删除量。 */
    public record CleanupResult(int activitiesRemoved, int tasksRemoved) {
        public int total() {
            return activitiesRemoved + tasksRemoved;
        }
    }

    /**
     * 删除早于 {@code cutoffMillis} 的历史。
     *
     * <p><b>务必用 {@link #purgeBefore(HistoryRepository,
     * TransactionRunner, long)} 那个带事务的重载</b>：
     * 不带事务时两类删除各自提交，中途失败会留下"任务清了、活动还在"的半截归档，
     * 报表会出现凭空少一半的实例。本重载仅为兼容与测试便利保留。
     *
     * @param cutoffMillis 分界时刻，严格早于它（{@code <}）的记录才会被删
     * @return 各类别删除条数
     */
    public static CleanupResult purgeBefore(HistoryRepository repo, long cutoffMillis) {
        Objects.requireNonNull(repo, "repo");
        int tasks = repo.deleteTasksBefore(cutoffMillis);
        // 活动必须走 deleteClosedBefore —— 它内部已排除未闭合记录
        int activities = repo.deleteClosedBefore(cutoffMillis);
        return new CleanupResult(activities, tasks);
    }

    /**
     * 在<b>单个事务</b>内完成两类清理 —— 这是推荐入口。
     *
     * <p>内存实现靠 undo log 回滚，JPA / MyBatis 靠共享同一事务的
     * EntityManager / SqlSession，语义一致。
     */
    public static CleanupResult purgeBefore(HistoryRepository repo,
                                            TransactionRunner tx,
                                            long cutoffMillis) {
        if (tx == null) {
            return purgeBefore(repo, cutoffMillis);
        }
        return tx.execute(() -> purgeBefore(repo, cutoffMillis));
    }

    /** 保留最近 {@code days} 天，更早的删掉（单事务）。 */
    public static CleanupResult purgeOlderThanDays(HistoryRepository repo,
                                                   TransactionRunner tx,
                                                   int days) {
        return purgeBefore(repo, tx, cutoffForDays(days));
    }

    /** 保留最近 {@code days} 天，更早的删掉。 */
    public static CleanupResult purgeOlderThanDays(HistoryRepository repo, int days) {
        return purgeBefore(repo, cutoffForDays(days));
    }

    private static long cutoffForDays(int days) {
        if (days <= 0) {
            throw new IllegalArgumentException("保留天数必须为正: " + days);
        }
        return System.currentTimeMillis() - days * DAY_MILLIS;
    }

    /**
     * 把毫秒换算成"约多少天"，仅用于日志与提示，不参与删除判定。
     */
    public static long toDays(long millis) {
        return millis / DAY_MILLIS;
    }
}
