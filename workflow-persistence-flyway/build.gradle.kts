// workflow-persistence-flyway: 统一 DDL 迁移模块
// 存放跨数据库兼容的 Flyway 迁移脚本,供 JPA / MyBatis-Plus 两种持久化实现共用
plugins {
    `java-library`
}

dependencies {
    // Flyway 核心(内置 H2 支持)
    api("org.flywaydb:flyway-core:12.8.1")
    // MySQL 需要独立模块(Flyway 10+ 拆分)
    api("org.flywaydb:flyway-mysql:12.8.1")
    // PostgreSQL 同样拆为独立模块(Flyway 12+)
    api("org.flywaydb:flyway-database-postgresql:12.8.1")

    // 日志(运行时使用)
    implementation("org.slf4j:slf4j-api:2.0.13")
}
