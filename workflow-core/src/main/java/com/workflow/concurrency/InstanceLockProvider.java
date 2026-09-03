package com.workflow.concurrency;

import java.util.function.Supplier;

/**
 * 实例锁提供者 —— 引擎并发安全的第一道防线。
 *
 * <p><b>锁粒度为什么是「流程树根」而不是「单个实例」</b>：
 * 子流程场景下引擎存在双向嵌套路径 ——
 * {@code startSubProcess}(父→子) 与 {@code onSubProcessCompleted}(子→父)。
 * 若按 instanceId 各自加锁，两个线程分别走这两条路径会构成 ABBA 死锁。
 * 统一锁到根实例后，同一棵流程树串行、不同树并行，无环且可重入。
 *
 * <p><b>与乐观锁的分工</b>：本接口解决<b>同一 JVM 内</b>的线程竞争（嵌入式引擎的主战场）；
 * 跨 JVM / 集群部署由仓储层的 revision 乐观锁兜底。两者互补，不可互相替代。
 */
public interface InstanceLockProvider {

    /**
     * 在指定流程树的锁保护下执行动作。
     *
     * @param rootInstanceId 流程树根实例 id（子流程须传其祖先根，而非自身 id）
     * @param action         临界区动作
     * @return 动作返回值
     */
    <T> T executeLocked(String rootInstanceId, Supplier<T> action);

    /** 在锁保护下执行无返回值动作。 */
    default void executeLocked(String rootInstanceId, Runnable action) {
        executeLocked(rootInstanceId, () -> {
            action.run();
            return null;
        });
    }
}
