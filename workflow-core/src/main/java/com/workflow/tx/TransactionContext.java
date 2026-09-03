package com.workflow.tx;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 当前线程的事务上下文。
 *
 * <p>供仓储层查询「本次写入是否处于某个事务内」，并在事务内登记
 * <b>before-image 恢复动作</b>（undo log）。真实数据库实现（JPA / MyBatis）
 * 也用它来共享同一个 EntityManager / Connection。
 *
 * <p>支持嵌套：内层 {@code execute} 复用外层事务，只有最外层负责提交或回滚。
 */
public final class TransactionContext {

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private TransactionContext() { }

    /** 当前线程是否处于事务中。 */
    public static boolean isActive() {
        Frame f = CURRENT.get();
        return f != null && f.depth > 0;
    }

    /** 当前嵌套深度（0 表示无事务）。 */
    public static int depth() {
        Frame f = CURRENT.get();
        return f == null ? 0 : f.depth;
    }

    /**
     * 登记一个恢复动作。
     *
     * <p>同一 {@code undoKey} 只保留<b>最早</b>的那份 before-image —— 它才是事务开始前的
     * 真实状态；后续覆盖会把「事务中途的脏值」误当成回滚目标。
     *
     * @param undoKey       去重键，约定为 {@code 仓储标识 + ':' + 主键}
     * @param restoreAction 回滚时执行的恢复动作
     */
    public static void recordUndo(String undoKey, Runnable restoreAction) {
        Frame f = CURRENT.get();
        if (f == null || f.depth == 0) {
            return; // 无事务，无需登记
        }
        f.undo.putIfAbsent(undoKey, restoreAction);
    }

    /**
     * 登记「事务成功提交后」才执行的动作。
     *
     * <p>用于那些<b>不受数据库事务保护</b>的副作用 —— 典型是超时调度器的
     * {@code cancel} / {@code schedule}：它们活在 JVM 内存里，一旦回滚，
     * 库中任务恢复了 PENDING 而调度却已被取消，就会出现「永不过期的待办」。
     * 登记在此处的动作只在提交后播放，回滚即丢弃。
     */
    public static void afterCommit(Runnable action) {
        Frame f = CURRENT.get();
        if (f == null || f.depth == 0) {
            action.run(); // 无事务包裹时立即执行，语义等同于「已经算数了」
            return;
        }
        f.afterCommit.add(action);
    }

    /** 取回当前事务已登记的 undo（测试与调试用）。 */
    public static int undoCount() {
        Frame f = CURRENT.get();
        return f == null ? 0 : f.undo.size();
    }

    /** 绑定一个已就绪的事务 frame（供 JPA / MyBatis 复用连接场景使用）。 */
    public static TransactionScope begin() {
        return new TransactionScope();
    }

    /** try-with-resources 形式的事务作用域句柄。 */
    public static final class TransactionScope implements AutoCloseable {
        private final Frame previous;
        private final Frame frame;

        TransactionScope() {
            this.previous = CURRENT.get();
            this.frame = (previous == null) ? new Frame() : previous;
            this.frame.depth++;
            CURRENT.set(this.frame);
        }

        /** 提交：最外层执行清空，内层仅减深度。 */
        public void commit() {
            if (frame.depth == 1) {
                List<Runnable> pending = new ArrayList<>(frame.afterCommit);
                frame.undo.clear();
                frame.afterCommit.clear();
                finish();
                // 提交后才播放：这些动作（调度器 cancel/schedule）不受事务保护
                for (Runnable action : pending) {
                    action.run();
                }
            } else {
                frame.depth--;
            }
        }

        /** 回滚：逆序执行全部 undo，丢弃 after-commit 副作用，最外层结束后解绑。 */
        public void rollback() {
            List<Map.Entry<String, Runnable>> entries =
                    new ArrayList<>(frame.undo.entrySet());
            Collections.reverse(entries);
            RuntimeException first = null;
            for (Map.Entry<String, Runnable> e : entries) {
                try {
                    e.getValue().run();
                } catch (RuntimeException ex) {
                    if (first == null) {
                        first = ex;
                    } else {
                        first.addSuppressed(ex);
                    }
                }
            }
            frame.undo.clear();
            frame.afterCommit.clear(); // 事务未生效，副作用一律作废
            frame.depth--;
            finish();
            if (first != null) {
                throw first;
            }
        }

        @Override
        public void close() {
            // 未显式 commit/rollback 时按回滚处理，防止连接泄漏
            if (frame.depth > 0) {
                frame.undo.clear();
                frame.afterCommit.clear();
                frame.depth--;
                finish();
            }
        }

        private void finish() {
            if (frame.depth <= 0) {
                if (previous != null) {
                    CURRENT.set(previous);
                } else {
                    CURRENT.remove();
                }
            }
        }
    }

    private static final class Frame {
        int depth;
        /** 保持登记顺序，回滚时逆序播放。 */
        final LinkedHashMap<String, Runnable> undo = new LinkedHashMap<>();
        /** 提交后才执行的副作用（调度器动作、外部通知等），回滚即丢弃。 */
        final List<Runnable> afterCommit = new ArrayList<>();
        /** 仓储可在此挂载实现私有状态（EntityManager / Connection）。 */
        final Deque<Object> attachments = new ArrayDeque<>();
    }

    // ---- 仓储挂载点：让 JPA / MyBatis 在同一线程内复用同一资源 ----

    /** 挂载实现私有资源（若已有则返回既有值，实现「一次事务一个连接」）。 */
    public static Object attachIfAbsent(Object candidate) {
        Frame f = CURRENT.get();
        if (f == null || f.depth == 0) {
            return null;
        }
        for (Object existing : f.attachments) {
            if (existing.getClass() == candidate.getClass()) {
                return existing;
            }
        }
        f.attachments.addLast(candidate);
        return null;
    }

    /** 取回本事务中某仓储已挂载的资源。 */
    @SuppressWarnings("unchecked")
    public static <T> T attached(Class<T> type) {
        Frame f = CURRENT.get();
        if (f == null) {
            return null;
        }
        for (Object existing : f.attachments) {
            if (type.isInstance(existing)) {
                return (T) existing;
            }
        }
        return null;
    }

    /** 事务结束时释放挂载资源（关闭连接等）。 */
    public static void detach(Class<?> type) {
        Frame f = CURRENT.get();
        if (f != null) {
            f.attachments.removeIf(o -> o.getClass() == type);
        }
    }
}
