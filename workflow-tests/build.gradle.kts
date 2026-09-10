// workflow-tests: 单元测试模块
dependencies {
    implementation(project(":workflow-core"))
    // JPA 测试需要 workflow-persistence-jpa 提供仓储实现
    testImplementation(project(":workflow-persistence-jpa"))
    // jakarta.persistence-api 用于 JPA 测试代码引用 EntityManager
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
    // MyBatis-Plus 测试需要 workflow-persistence-mybatis 提供仓储实现
    testImplementation(project(":workflow-persistence-mybatis"))

    // ---- 跨数据库测试(MySQL / PostgreSQL,基于 Testcontainers) ----
    // Testcontainers BOM 统一版本(1.21.4 为 1.x 最新,含 mysql/postgresql 模块)
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:postgresql")
    // JDBC 驱动
    testImplementation("com.mysql:mysql-connector-j:8.4.0")
    testImplementation("org.postgresql:postgresql:42.7.4")

    // 测试相关依赖由根 build.gradle.kts 统一通过 testImplementation 引入
}

// 每个测试类 fork 独立 JVM：消除跨类静态状态耦合。
// JpaPersistence/MybatisPersistence 是单例、H2 为命名内存库、TransactionContext 为 ThreadLocal，
// 多类共跑时会相互污染(如 JPA 事务原子性测试混跑偶现回滚失效)。forkEvery=1 让静态状态随 JVM 重置。
tasks.test {
    forkEvery = 1
    maxParallelForks = 4
    // 透传给测试 JVM：默认关闭，`gradle test -Dperf=true` 才启用性能基准用例
    systemProperty("perf", System.getProperty("perf", ""))

    // 主 CI 用 `-PskipCrossDb=true` 跳过需 Docker 的跨库用例(Testcontainers 拉镜像慢、
    // 环境易抖动，不该阻塞主构建)。不加该属性时照常纳入运行(本地无 Docker 会自动 skip)。
    if (project.findProperty("skipCrossDb") == "true") {
        filter { excludeTestsMatching("com.workflow.tests.crossdb.*") }
    }
}
