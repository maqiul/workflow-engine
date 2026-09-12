package com.workflow.tx;

import java.sql.Connection;

/**
 * 外部事务资源提供者 SPI —— 让宿主把「当前线程已有事务」的数据库连接交给引擎。
 *
 * <p>嵌入式集成（引擎 jar 进宿主进程、与宿主**同库**）时，宿主通常自己管着事务：
 * Spring 的 {@code @Transactional} 会把连接绑在 {@code TransactionSynchronizationManager} 上。
 * 引擎若从自建连接池另取一条连接，两边就是**两笔独立事务** —— 宿主回滚不会回滚引擎写入，
 * 于是出现「宿主业务失败但流程已推进」这类无法自愈的脏数据。
 *
 * <p>实现本接口后，引擎的写入会复用宿主那条连接，从而真正并入宿主事务：
 *
 * <pre>
 * // 宿主编写（以 Spring 为例，约 10 行，引擎本身不依赖 Spring）
 * public class SpringTxProvider implements ExternalTransactionProvider {
 *     private final DataSource dataSource;
 *
 *     public Connection currentConnection() {
 *         return TransactionSynchronizationManager.isActualTransactionActive()
 *                 ? DataSourceUtils.getConnection(dataSource)
 *                 : null;
 *     }
 * }
 * </pre>
 *
 * <p><b>契约</b>：引擎只使用返回的连接执行 SQL，**既不 commit / rollback，也不 close**
 * （连接归宿主管理；Spring 返回的代理连接 close 本就是 no-op）。
 * 返回 {@code null} 表示当前线程没有宿主事务，引擎按自身事务边界执行。
 *
 * <p>未注入时引擎行为完全不变（见 {@link #none()}）。
 */
public interface ExternalTransactionProvider {

    /**
     * 返回当前线程所处的宿主事务连接。
     *
     * @return 宿主事务的连接；当前线程无宿主事务时返回 {@code null}
     */
    Connection currentConnection();

    /** 空实现：永远没有宿主事务，引擎走自己的事务边界。 */
    static ExternalTransactionProvider none() {
        return () -> null;
    }
}
