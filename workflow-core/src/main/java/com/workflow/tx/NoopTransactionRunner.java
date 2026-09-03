package com.workflow.tx;

/**
 * 空事务实现：不开事务，直接执行。
 *
 * <p>用于向后兼容 —— 未注入 {@link TransactionRunner} 的调用方（如 v3.6 之前的装配代码）
 * 行为保持不变。生产装配应显式使用 {@link UndoLogTransactionRunner}（InMemory）
 * 或数据库实现。
 */
enum NoopTransactionRunner implements TransactionRunner {

    INSTANCE;

    @Override
    public <T> T execute(java.util.function.Supplier<T> work) {
        return work.get();
    }

    @Override
    public boolean isActive() {
        return false;
    }
}
