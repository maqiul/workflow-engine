package com.workflow.tx;

import java.util.function.Supplier;

/**
 * 事务边界抽象。
 *
 * <p>引擎的一次业务动作（发起 / 完成 / 驳回 / 转办 / 终止 / 撤回）会跨多个仓储写入：
 * {@code instance}、{@code task}、{@code token}、{@code auditLog} 乃至 {@code carbonCopy}。
 * 这些写入必须整体生效或整体不生效，否则实例会停在「任务已 COMPLETED 但 Token 未推进」
 * 这类无法自愈的半完成状态。
 *
 * <p>各持久化实现的落地方式：
 * <ul>
 *   <li>InMemory —— before-image undo log（见 {@link UndoLogTransactionRunner}）</li>
 *   <li>JPA —— 共享同一 {@code EntityTransaction}，仓储复用 ThreadLocal 中的 EntityManager</li>
 *   <li>MyBatis —— 共享同一 {@code Connection}，关闭 autoCommit</li>
 * </ul>
 *
 * <p>未显式注入时引擎使用 {@link #noop()}，行为等同于 v3.6 之前的「每仓储各自成事务」，
 * 以保证向后兼容。
 */
public interface TransactionRunner {

    /** 在一个事务中执行并返回结果；抛异常则回滚。 */
    <T> T execute(Supplier<T> work);

    /** 在一个事务中执行无返回值动作。 */
    default void execute(Runnable work) {
        execute(() -> {
            work.run();
            return null;
        });
    }

    /** 当前线程是否处于事务中。仓储据此决定是否登记 undo / 是否自行提交。 */
    default boolean isActive() {
        return TransactionContext.isActive();
    }

    /** 空实现：不开事务，直接执行。 */
    static TransactionRunner noop() {
        return NoopTransactionRunner.INSTANCE;
    }
}
