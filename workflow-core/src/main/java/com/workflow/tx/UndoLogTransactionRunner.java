package com.workflow.tx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * 内存版事务：基于 before-image undo log。
 *
 * <p>InMemory 仓储存放的是<b>对象引用</b>，引擎又在引用上原地改状态，所以
 * 「把旧引用放回 map」并不能撤销 —— 那个对象的内部已经被改坏了。必须登记
 * <b>深拷贝快照</b>作为恢复目标，这正是各仓储 {@code save} 内调用
 * {@link TransactionContext#recordUndo} 时传入的内容。
 *
 * <p>嵌套事务复用外层 undo 集合，只有最外层提交或回滚。
 */
public class UndoLogTransactionRunner implements TransactionRunner {

    private static final Logger log = LoggerFactory.getLogger(UndoLogTransactionRunner.class);

    @Override
    public <T> T execute(Supplier<T> work) {
        boolean outermost = !TransactionContext.isActive();
        try (TransactionContext.TransactionScope scope = TransactionContext.begin()) {
            try {
                T result = work.get();
                scope.commit();
                return result;
            } catch (RuntimeException | Error ex) {
                if (outermost && log.isDebugEnabled()) {
                    log.debug("[事务] 最外层事务失败，开始回滚 undo 条目={}", TransactionContext.undoCount());
                }
                scope.rollback();
                throw ex;
            }
        }
    }
}
