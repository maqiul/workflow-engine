package com.workflow.persistence.jpa;

import com.workflow.persistence.jpa.repository.JpaAuditLogRepository;
import com.workflow.persistence.jpa.repository.JpaInstanceRepository;
import com.workflow.persistence.jpa.repository.JpaProcessRepository;
import com.workflow.persistence.jpa.repository.JpaTaskRepository;
import com.workflow.persistence.migrate.FlywayMigrator;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * JPA 持久化入口 - 单例工厂类
 *
 * 用法:
 * <pre>{@code
 *   JpaPersistence jpa = JpaPersistence.getDefault();
 *   jpa.init();   // 初始化 EntityManagerFactory
 *
 *   ProcessRepository procRepo = jpa.processRepo();
 *   InstanceRepository instRepo = jpa.instanceRepo();
 *   TaskRepository taskRepo = jpa.taskRepo();
 *
 *   WorkflowEngine engine = new WorkflowEngine(procRepo, instRepo, taskRepo);
 *
 *   jpa.close();  // 应用退出时
 * }</pre>
 *
 * 设计要点:
 *  - 单例 EMF:EntityManagerFactory 创建开销大,全局共享一个
 *  - 仓储无状态:每次调用基于当前线程拿 EntityManager
 *  - 简化事务:用 EntityTransaction 控制,生产可换成 JTA 或 Spring @Transactional
 */
public class JpaPersistence {

    private static final Logger log = LoggerFactory.getLogger(JpaPersistence.class);

    private static final JpaPersistence DEFAULT = new JpaPersistence();

    public static JpaPersistence getDefault() {
        return DEFAULT;
    }

    private EntityManagerFactory emf;
    private final Map<String, String> props = new HashMap<>();

    /** 默认 H2 内存库连接(与 persistence.xml 一致,供 Flyway 迁移用) */
    private static final String DEFAULT_JDBC_URL = "jdbc:h2:mem:workflow_db;DB_CLOSE_DELAY=-1;MODE=MySQL";
    private static final String DEFAULT_JDBC_USER = "sa";
    private static final String DEFAULT_JDBC_PASSWORD = "";

    /** 默认 H2 内存模式初始化 */
    public synchronized void init() {
        init(new HashMap<>());
    }

    /** 用自定义属性初始化(可覆盖 persistence.xml 中的配置) */
    public synchronized void init(Map<String, String> overrideProps) {
        if (emf != null && emf.isOpen()) {
            log.warn("[JPA] EntityManagerFactory 已打开,跳过重复初始化");
            return;
        }
        // 先清空旧属性,避免跨库切换时残留上一次的 JDBC 连接配置
        props.clear();
        props.putAll(overrideProps);
        log.info("[JPA] 初始化 EntityManagerFactory, overrides={}", props);
        // 先执行 Flyway 迁移统一建表,再创建 EMF(Hibernate hbm2ddl=none 不再建表)
        migrateWithProps();
        emf = Persistence.createEntityManagerFactory("workflow-pu", props);
    }

    /** 从 props 解析 JDBC 连接,执行 Flyway 迁移(表已迁移则跳过) */
    private void migrateWithProps() {
        String url = props.getOrDefault("jakarta.persistence.jdbc.url", DEFAULT_JDBC_URL);
        String user = props.getOrDefault("jakarta.persistence.jdbc.user", DEFAULT_JDBC_USER);
        String password = props.getOrDefault("jakarta.persistence.jdbc.password", DEFAULT_JDBC_PASSWORD);
        FlywayMigrator.migrate(url, user, password);
    }

    public synchronized void close() {
        if (emf != null && emf.isOpen()) {
            emf.close();
            log.info("[JPA] EntityManagerFactory 已关闭");
        }
        emf = null;
    }

    /**
     * 提供 ThreadLocal 的当前事务 EM,允许跨仓储调用合并到同一事务。
     * 测试场景:让引擎一个 completeTask/transferTask 操作中,instance + token + task
     * 都在同一个 EM 内、同一事务内落盘。
     */
    private final ThreadLocal<EntityManager> currentEm = new ThreadLocal<>();

    /** 把当前线程的 EM 绑定到 ThreadLocal,返回 EM(测试用) */
    public EntityManager bindCurrentEm() {
        EntityManager em = emf().createEntityManager();
        em.getTransaction().begin();
        currentEm.set(em);
        return em;
    }

    /** 提交并解绑当前 EM */
    public void commitAndUnbind() {
        EntityManager em = currentEm.get();
        if (em != null) {
            try {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().commit();
                }
            } finally {
                currentEm.remove();
                em.close();
            }
        }
    }

    /** 回滚并解绑当前 EM */
    public void rollbackAndUnbind() {
        EntityManager em = currentEm.get();
        if (em != null) {
            try {
                if (em.getTransaction().isActive()) {
                    em.getTransaction().rollback();
                }
            } finally {
                currentEm.remove();
                em.close();
            }
        }
    }

    /** 拿当前线程已绑定的 EM,没绑定会抛错 */
    public EntityManager currentEm() {
        EntityManager em = currentEm.get();
        if (em == null) {
            throw new IllegalStateException("未在事务上下文中");
        }
        return em;
    }

    /** 拿当前线程已绑定的 EM,没绑定返回 null */
    public EntityManager currentEmOrNull() {
        return currentEm.get();
    }

    /** 当前事务是否激活 */
    public boolean isInTransaction() {
        return currentEm.get() != null;
    }

    /** 当前 EntityManagerFactory,未初始化会抛错 */
    public EntityManagerFactory emf() {
        Objects.requireNonNull(emf, "请先调用 init()");
        return emf;
    }

    /** 新建 EntityManager(线程不安全,每次用完必须 close) */
    public EntityManager newEntityManager() {
        return emf().createEntityManager();
    }

    /** 提供 ProcessRepository - 仓储无状态,共享即可 */
    public ProcessRepository processRepo() {
        return new JpaProcessRepository(this);
    }

    /** 提供 InstanceRepository */
    public InstanceRepository instanceRepo() {
        return new JpaInstanceRepository(this);
    }

    /** 提供 TaskRepository */
    public TaskRepository taskRepo() {
        return new JpaTaskRepository(this);
    }

    /** 提供 AuditLogRepository */
    public AuditLogRepository auditLogRepo() {
        return new JpaAuditLogRepository(this);
    }

    /**
     * 共享 EntityManager - 用 EMF 自带的 session 模式。
     * 注:这里简化,每个仓储拿独立 EM。生产建议每次业务操作开新 EM + 事务。
     */
    private EntityManager sharedEntityManager() {
        return newEntityManager();
    }

    /**
     * 便捷事务模板 —— <b>事务感知</b>：引擎已在事务中时复用同一个 EntityManager，
     * 否则自开一个独立短事务。
     *
     * <p>这是让「一次 completeTask 跨 instance / task / token / audit 四次写入」
     * 真正原子的关键。此前每个仓储各自 {@code newEntityManager()} + 各自提交，
     * 中途失败会留下已提交一半的实例状态 —— 内存版的 undo log 修不了它，
     * 因为数据库根本不参与那套 undo。
     *
     * <p>共享 EM 由 {@link TransactionContext} 挂载，首个使用者（即引擎动作内的第一个
     * 仓储调用）负责开启并登记提交/回滚钩子；后续仓储复用同一实例。
     */
    public <T> T inTransaction(java.util.function.Function<EntityManager, T> work) {
        if (com.workflow.tx.TransactionContext.isActive()) {
            return inSharedTransaction(work);
        }
        return inStandaloneTransaction(work);
    }

    /** 独立短事务：供引擎之外的手工调用（如测试清表）使用。 */
    private <T> T inStandaloneTransaction(java.util.function.Function<EntityManager, T> work) {
        EntityManager em = newEntityManager();
        EntityTransaction tx = em.getTransaction();
        try {
            tx.begin();
            T result = work.apply(em);
            tx.commit();
            return result;
        } catch (RuntimeException ex) {
            if (tx.isActive()) tx.rollback();
            throw ex;
        } finally {
            em.close();
        }
    }

    /** 加入引擎已开启的事务，复用本线程本事务的 EntityManager。 */
    private <T> T inSharedTransaction(java.util.function.Function<EntityManager, T> work) {
        ManagedEm holder = com.workflow.tx.TransactionContext.attached(ManagedEm.class);
        if (holder == null) {
            final EntityManager em = newEntityManager();
            em.getTransaction().begin();
            holder = new ManagedEm(em);
            com.workflow.tx.TransactionContext.attachIfAbsent(holder);
            final ManagedEm bound = holder;
            // 提交：先落盘再结束数据库事务，无论如何都要关掉 EM 防泄漏
            com.workflow.tx.TransactionContext.beforeCommit(() -> {
                try {
                    em.flush();
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().commit();
                    }
                } catch (RuntimeException ex) {
                    try {
                        if (em.getTransaction().isActive()) em.getTransaction().rollback();
                    } finally {
                        em.close();
                    }
                    throw ex;
                } finally {
                    if (em.isOpen()) em.close();
                }
            });
            // 回滚：撤销数据库改动并释放 EM
            com.workflow.tx.TransactionContext.onRollback(() -> {
                try {
                    if (em.getTransaction().isActive()) {
                        em.getTransaction().rollback();
                    }
                } finally {
                    if (em.isOpen()) em.close();
                }
            });
        }
        EntityManager em = holder.em;
        T result = work.apply(em);
        // 原生查询不会触发 Hibernate 自动 flush；不主动 flush，
        // 后一个仓储的原生 DELETE/SELECT 就看不见前一个仓储刚写的行。
        em.flush();
        return result;
    }

    /** 本事务共享的 EntityManager 包装（用作 {@code TransactionContext} 的挂载标识）。 */
    private static final class ManagedEm {
        final EntityManager em;
        ManagedEm(EntityManager em) { this.em = em; }
    }

    /**
     * 提供一个便利方法:init + 一次性创建 3 个仓储,装配好给 WorkflowEngine 用
     */
    public Repositories createRepositories() {
        init();
        return new Repositories(processRepo(), instanceRepo(), taskRepo());
    }

    /** 3 个仓储的简单包装 */
    public record Repositories(ProcessRepository processRepo,
                               InstanceRepository instanceRepo,
                               TaskRepository taskRepo) {}
}