package com.workflow.concurrency;

import com.workflow.WorkflowException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单机分段锁实现：每个流程树根一把可重入锁。
 *
 * <p>要点：
 * <ul>
 *   <li><b>可重入</b> —— {@code advanceToken} 递归下钻、子流程回调都会重复进入同一棵树。</li>
 *   <li><b>有界等待</b> —— 用 {@code tryLock(timeout)} 而非 {@code lock()}，避免某个监听器
 *       或下游仓储卡死时把整棵流程树永久钉住；超时抛 {@link LockAcquisitionException}。</li>
 *   <li><b>引用计数回收</b> —— 锁对象随临界区结束且无持有者时从表中移除，
 *       否则长期运行的服务会按流程实例数无界堆积锁对象。</li>
 * </ul>
 *
 * <p>仅对单 JVM 有效。多实例部署须叠加仓储层乐观锁（见 {@code revision}）。
 */
public class LocalInstanceLocks implements InstanceLockProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalInstanceLocks.class);

    /** 默认最长等锁时间：30 秒。 */
    public static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();
    private final long timeoutMillis;

    public LocalInstanceLocks() {
        this(DEFAULT_TIMEOUT_MILLIS);
    }

    public LocalInstanceLocks(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public <T> T executeLocked(String rootInstanceId, Supplier<T> action) {
        LockEntry entry = acquire(rootInstanceId);
        boolean locked = false;
        try {
            locked = entry.lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new LockAcquisitionException(
                        "获取流程实例锁超时(" + timeoutMillis + "ms): root=" + rootInstanceId
                                + "，可能存在长事务或死锁");
            }
            return action.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("等待流程实例锁被中断: root=" + rootInstanceId, ex);
        } finally {
            if (locked) {
                entry.lock.unlock();
            }
            release(rootInstanceId, entry);
        }
    }

    /** 当前持有的锁数量，用于观测与测试断言（应为 0，说明无泄漏）。 */
    public int heldLockCount() {
        return locks.size();
    }

    private LockEntry acquire(String rootInstanceId) {
        return locks.compute(rootInstanceId, (id, existing) -> {
            LockEntry entry = (existing != null) ? existing : new LockEntry();
            entry.refCount++;
            return entry;
        });
    }

    private void release(String rootInstanceId, LockEntry entry) {
        locks.compute(rootInstanceId, (id, current) -> {
            if (current != entry) {
                // 表项已被其它线程替换，本次计数不再属于我们
                return current;
            }
            entry.refCount--;
            if (entry.refCount <= 0 && !entry.lock.isLocked()) {
                return null; // 无人持有 → 回收，防止无界堆积
            }
            return entry;
        });
    }

    private static final class LockEntry {
        final ReentrantLock lock =
                new ReentrantLock(true);
        /** 仅在 {@code locks.compute} 内读写，由 CHM 的 bin 锁保护。 */
        int refCount;
    }

    /** 获取实例锁失败。 */
    public static class LockAcquisitionException extends WorkflowException {
        public LockAcquisitionException(String message) {
            super(message);
        }

        public LockAcquisitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
