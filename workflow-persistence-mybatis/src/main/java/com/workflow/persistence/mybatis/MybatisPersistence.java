package com.workflow.persistence.mybatis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.workflow.persistence.migrate.FlywayMigrator;
import com.workflow.persistence.mybatis.mapper.WfAuditLogMapper;
import com.workflow.persistence.mybatis.mapper.WfProcessDefMapper;
import com.workflow.persistence.mybatis.mapper.WfInstanceMapper;
import com.workflow.persistence.mybatis.mapper.WfTaskMapper;
import com.workflow.persistence.mybatis.mapper.WfTokenMapper;
import com.workflow.persistence.mybatis.repository.MybatisAuditLogRepository;
import com.workflow.persistence.mybatis.repository.MybatisInstanceRepository;
import com.workflow.persistence.mybatis.repository.MybatisProcessRepository;
import com.workflow.persistence.mybatis.repository.MybatisTaskRepository;
import com.workflow.repository.AuditLogRepository;
import com.workflow.repository.InstanceRepository;
import com.workflow.repository.ProcessRepository;
import com.workflow.repository.TaskRepository;
import com.workflow.tx.ExternalTransactionProvider;
import com.workflow.tx.TransactionContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.function.Function;

/**
 * MyBatis-Plus 持久化入口(单例工厂)
 *
 * 无 Spring 轻量用法:
 *  1. HikariCP 数据源(默认 H2 内存库,支持 MySQL/PostgreSQL 切换)
 *  2. Flyway 统一建表(db/migration/V1__init.sql,跨库同一份 DDL)
 *  3. MybatisConfiguration + MybatisSqlSessionFactoryBuilder 构建 SqlSessionFactory
 *  4. inSession 模板:每次操作独立 SqlSession + 事务,与 JpaPersistence.inTransaction 对齐
 *
 * 用法:
 *  <pre>
 *  MybatisPersistence.init();
 *  ProcessRepository  pr = MybatisPersistence.getDefault().processRepo();
 *  ...
 *  MybatisPersistence.close();
 *  </pre>
 */
public final class MybatisPersistence {

    private static final Logger log = LoggerFactory.getLogger(MybatisPersistence.class);

    private static volatile MybatisPersistence defaultInstance;

    /**
     * 嵌入式集成时引擎专用的 Flyway schema history 表名。
     *
     * <p>不能沿用默认的 {@code flyway_schema_history}:引擎与宿主<b>同库</b>时两者会抢同一张历史表,
     * 宿主已有的 {@code V1__xxx} 会让引擎的 {@code V1__init} 被判为「已执行」而静默跳过建表,
     * 或直接抛「Found more than one migration with version」。
     */
    private static final String ENGINE_HISTORY_TABLE = "wf_schema_history";

    /** 连接来源:自建 Hikari,或嵌入式集成时宿主注入的 DataSource */
    private DataSource dataSource;

    /** 连接池是否由引擎自建(true 才由 {@link #close()} 关闭);宿主注入时为 false */
    private boolean ownsDataSource;

    private SqlSessionFactory sqlSessionFactory;

    /** 宿主事务资源提供者(嵌入式集成时注入);默认无宿主事务,行为与 v3.18 完全一致 */
    private volatile ExternalTransactionProvider externalTransactionProvider = ExternalTransactionProvider.none();

    // 仓储实例(懒创建)
    private volatile MybatisProcessRepository processRepo;
    private volatile MybatisInstanceRepository instanceRepo;
    private volatile MybatisTaskRepository taskRepo;
    private volatile MybatisAuditLogRepository auditLogRepo;

    private MybatisPersistence() {
    }

    public static MybatisPersistence getDefault() {
        if (defaultInstance == null) {
            synchronized (MybatisPersistence.class) {
                if (defaultInstance == null) {
                    defaultInstance = new MybatisPersistence();
                }
            }
        }
        return defaultInstance;
    }

    /** 初始化(默认 H2 内存库) */
    public synchronized void init() {
        if (sqlSessionFactory != null) {
            log.warn("MybatisPersistence 已初始化,忽略重复调用");
            return;
        }
        long t0 = System.currentTimeMillis();
        initDataSource("jdbc:h2:mem:workflow_mybatis;DB_CLOSE_DELAY=-1", "sa", "");
        FlywayMigrator.migrate(dataSource);
        buildSqlSessionFactory();
        log.info("MybatisPersistence 初始化完成,耗时 {}ms", System.currentTimeMillis() - t0);
    }

    /** 初始化(自定义 JDBC URL,如 MySQL/PostgreSQL 演示) */
    public synchronized void init(String jdbcUrl, String user, String password) {
        if (sqlSessionFactory != null) {
            log.warn("MybatisPersistence 已初始化,忽略重复调用");
            return;
        }
        long t0 = System.currentTimeMillis();
        initDataSource(jdbcUrl, user, password);
        FlywayMigrator.migrate(dataSource);
        buildSqlSessionFactory();
        log.info("MybatisPersistence 初始化完成 url={}, 耗时 {}ms", jdbcUrl, System.currentTimeMillis() - t0);
    }

    /**
     * 接入宿主已有的 DataSource —— <b>嵌入式集成入口</b>。
     *
     * <p>与 {@link #init(String, String, String)} 的三点区别:
     * <ol>
     *   <li>连接来自宿主连接池,引擎不自建 Hikari;{@link #close()} 也<b>不会</b>关闭宿主的池</li>
     *   <li>建表用引擎专属 history 表 {@code wf_schema_history},不与宿主的 Flyway 抢表</li>
     *   <li>配合 {@link #externalTransactionProvider} 后,引擎写入并入宿主事务</li>
     * </ol>
     *
     * <p><b>引擎仍然自建 SqlSessionFactory</b>(保留自己的 MyBatis-Plus 插件链 —— 尤其是
     * 乐观锁 {@code OptimisticLockerInnerInterceptor})。不要图省事共享宿主的 SqlSessionFactory:
     * 宿主的插件链里通常没有它,引擎的 {@code @Version} 乐观锁会<b>静默失效</b>,并发写冲突无人拦截。
     */
    public synchronized void withDataSource(DataSource dataSource) {
        withDataSource(dataSource, true);
    }

    /**
     * 接入宿主已有的 DataSource —— <b>嵌入式集成入口</b>。
     *
     * @param migrate 是否执行建表迁移。宿主由 DBA 统一管 DDL 时传 {@code false}
     *                (表须已按 {@code classpath:db/migration} 的脚本建好)
     */
    public synchronized void withDataSource(DataSource dataSource, boolean migrate) {
        if (sqlSessionFactory != null) {
            log.warn("MybatisPersistence 已初始化,忽略重复调用");
            return;
        }
        long t0 = System.currentTimeMillis();
        this.dataSource = dataSource;
        this.ownsDataSource = false;
        if (migrate) {
            FlywayMigrator.migrate(dataSource, ENGINE_HISTORY_TABLE);
        } else {
            log.info("MybatisPersistence 跳过建表迁移(由宿主管理 DDL)");
        }
        buildSqlSessionFactory();
        log.info("MybatisPersistence 已接入宿主 DataSource, migrate={}, 耗时 {}ms",
                migrate, System.currentTimeMillis() - t0);
    }

    /**
     * 注入宿主事务资源提供者 —— 让引擎写入并入宿主的 {@code @Transactional}。
     *
     * <p>注入后 {@link #inSession} 优先复用宿主当前事务的连接,引擎不提交、不回滚、不关闭它。
     * 未注入时行为与 v3.18 完全一致。
     */
    public synchronized void externalTransactionProvider(ExternalTransactionProvider provider) {
        this.externalTransactionProvider = provider == null
                ? ExternalTransactionProvider.none()
                : provider;
    }

    /** 按 JDBC URL 自动识别驱动类 */
    private static String driverOf(String jdbcUrl) {
        if (jdbcUrl.startsWith("jdbc:mysql:")) {
            return "com.mysql.cj.jdbc.Driver";
        }
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            return "org.postgresql.Driver";
        }
        return "org.h2.Driver";
    }

    private void initDataSource(String jdbcUrl, String user, String password) {
        ownsDataSource = true;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setDriverClassName(driverOf(jdbcUrl));
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(2);
        config.setPoolName("workflow-mybatis");
        dataSource = new HikariDataSource(config);
    }

    private void buildSqlSessionFactory() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("workflow", new JdbcTransactionFactory(), dataSource));
        // 注册 Mapper 接口
        configuration.addMapper(WfProcessDefMapper.class);
        configuration.addMapper(WfInstanceMapper.class);
        configuration.addMapper(WfTokenMapper.class);
        configuration.addMapper(WfTaskMapper.class);
        configuration.addMapper(WfAuditLogMapper.class);
        // 历史活动：漏掉这行不会编译报错，只在 getMapper 时抛运行时异常
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfHistActivityMapper.class);
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfHistTaskMapper.class);
        // 事件网关
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfEventMapper.class);
        // DMN 决策表和历史
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfDecisionTableMapper.class);
        configuration.addMapper(com.workflow.persistence.mybatis.mapper.WfDecisionHistoryMapper.class);
        // 驼峰映射默认开启
        configuration.setMapUnderscoreToCamelCase(true);
        // 乐观锁插件：把 @Version 实体上的 updateById 改写成
        //   UPDATE ... SET revision = revision + 1 WHERE id = ? AND revision = ?
        // 不注册它，@Version 就只是个普普通通的长整型字段，CAS 形同虚设。
        // 注意 InnerInterceptor 不是 MyBatis 原生 Interceptor，必须由
        // MybatisPlusInterceptor 包一层才能挂到 Configuration 上。
        MybatisPlusInterceptor plugins = new MybatisPlusInterceptor();
        plugins.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(plugins);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    /**
     * 事务模板 —— <b>事务感知</b>：引擎已在事务中时复用同一个 SqlSession，
     * 否则自开独立短事务。
     *
     * <p>与 {@code JpaPersistence.inTransaction} 同理：一次 {@code completeTask}
     * 要写 instance / token / task / audit 四张表，各仓储各自开 session 就等于
     * 四次独立提交，中途失败留下半完成状态。
     *
     * <p>MyBatis 无需像 Hibernate 那样手动 flush —— SQL 立即下发；而任何 update
     * 都会清掉 session 级本地缓存，所以同一 session 内读写交替能看到最新值。
     */
    public <T> T inSession(Function<SqlSession, T> action) {
        Connection external = externalConnection();
        if (external != null) {
            return inExternalSession(external, action);
        }
        if (TransactionContext.isActive()) {
            return inSharedSession(action);
        }
        return inStandaloneSession(action);
    }

    /**
     * 并入宿主事务:复用宿主当前事务那条连接执行 SQL。
     *
     * <p>同一条连接即同一个事务 —— 宿主的 commit / rollback 会连带覆盖引擎的写入。
     * 引擎<b>不提交、不回滚、不关闭</b>这条连接(归宿主管理;Spring 返回的代理连接 close 本就是 no-op)。
     * 异常直接向上传播,由宿主的事务框架决定回滚。
     *
     * <p>session 即用即弃、不做线程缓存:一次业务动作内部引擎已用外层 {@code inSession}
     * 包住 instance / token / task / audit 多张表的写入,只会在最外层开一次 session。
     */
    private <T> T inExternalSession(Connection connection, Function<SqlSession, T> action) {
        SqlSession session = sqlSessionFactory.openSession(ExecutorType.SIMPLE, connection);
        return action.apply(session);
    }

    /** 取宿主事务连接;无 provider、无宿主事务、或 provider 抛异常时返回 null(回退引擎自身事务边界) */
    private Connection externalConnection() {
        try {
            return externalTransactionProvider.currentConnection();
        } catch (RuntimeException ex) {
            log.warn("ExternalTransactionProvider 取连接失败,回退到引擎自身事务边界", ex);
            return null;
        }
    }

    /** 独立短事务：供引擎之外的手工调用（如测试清表）使用。 */
    private <T> T inStandaloneSession(Function<SqlSession, T> action) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            T result = action.apply(session);
            session.commit();
            return result;
        }
    }

    /** 加入引擎已开启的事务，复用本线程本事务的 SqlSession。 */
    private <T> T inSharedSession(Function<SqlSession, T> action) {
        ManagedSession holder = TransactionContext.attached(ManagedSession.class);
        if (holder == null) {
            final SqlSession session = sqlSessionFactory.openSession(false);
            holder = new ManagedSession(session);
            TransactionContext.attachIfAbsent(holder);
            TransactionContext.beforeCommit(() -> {
                try {
                    session.commit();
                } catch (RuntimeException ex) {
                    try {
                        session.rollback();
                    } finally {
                        session.close();
                    }
                    throw ex;
                } finally {
                    session.close();
                }
            });
            TransactionContext.onRollback(() -> {
                try {
                    session.rollback();
                } finally {
                    session.close();
                }
            });
        }
        return action.apply(holder.session);
    }

    /** 本事务共享的 SqlSession 包装（用作 {@code TransactionContext} 的挂载标识）。 */
    private static final class ManagedSession {
        final SqlSession session;
        ManagedSession(SqlSession session) { this.session = session; }
    }

    /** 清理所有表数据(测试用) */
    public void clearTables() {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DELETE FROM wf_hist_task");
            st.execute("DELETE FROM wf_hist_activity");
            st.execute("DELETE FROM wf_event");
            st.execute("DELETE FROM wf_audit_log");
            st.execute("DELETE FROM wf_task");
            st.execute("DELETE FROM wf_token");
            st.execute("DELETE FROM wf_instance");
            st.execute("DELETE FROM wf_process_def");
        } catch (Exception ex) {
            throw new RuntimeException("清理表数据失败: " + ex.getMessage(), ex);
        }
    }

    public synchronized void close() {
        if (dataSource != null) {
            // 只能关引擎自建的池。宿主注入的 DataSource 归宿主所有,
            // 关掉它等于关掉宿主整个应用的连接池。
            if (ownsDataSource) {
                closeOwnedPool();
            }
            dataSource = null;
            ownsDataSource = false;
            sqlSessionFactory = null;
            processRepo = null;
            instanceRepo = null;
            taskRepo = null;
            auditLogRepo = null;
        }
    }

    /** 关闭引擎自建连接池(按 {@code AutoCloseable} 语义关闭,不要求 Hikari 具体类型) */
    private void closeOwnedPool() {
        try {
            if (dataSource instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception ex) {
            log.warn("关闭自建连接池失败", ex);
        }
    }

    public DataSource dataSource() {
        return dataSource;
    }

    public SqlSessionFactory sqlSessionFactory() {
        return sqlSessionFactory;
    }

    public ProcessRepository processRepo() {
        if (processRepo == null) {
            processRepo = new MybatisProcessRepository(this);
        }
        return processRepo;
    }

    public InstanceRepository instanceRepo() {
        if (instanceRepo == null) {
            instanceRepo = new MybatisInstanceRepository(this);
        }
        return instanceRepo;
    }

    public TaskRepository taskRepo() {
        if (taskRepo == null) {
            taskRepo = new MybatisTaskRepository(this);
        }
        return taskRepo;
    }

    public AuditLogRepository auditLogRepo() {
        if (auditLogRepo == null) {
            auditLogRepo = new MybatisAuditLogRepository(this);
        }
        return auditLogRepo;
    }

    /** 提供 HistoryRepository（历史活动区间，见 README §18）。 */
    public com.workflow.repository.HistoryRepository historyRepo() {
        return new com.workflow.persistence.mybatis.repository.MybatisHistoryRepository(this);
    }

    /** 提供 EventRepository（事件网关：消息/信号/定时器）。 */
    public com.workflow.repository.EventRepository eventRepo() {
        return new com.workflow.persistence.mybatis.repository.MybatisEventRepository(this);
    }

    /** 提供 DecisionRepository（DMN 决策表）。 */
    public com.workflow.dmn.DecisionRepository decisionRepo() {
        return new com.workflow.persistence.mybatis.repository.MybatisDecisionRepository(this);
    }

    /** 提供 DecisionHistoryRepository（DMN 决策历史）。 */
    public com.workflow.dmn.DecisionHistoryRepository decisionHistoryRepo() {
        return new com.workflow.persistence.mybatis.repository.MybatisDecisionHistoryRepository(this);
    }
}
