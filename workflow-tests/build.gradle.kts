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
