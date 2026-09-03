// workflow-persistence-jpa: JPA 持久化实现
plugins {
    `java-library`
    application
}

application {
    mainClass.set("com.workflow.persistence.jpa.JpaPersistence")
}

dependencies {
    // 引擎核心接口 - 用 api 配置让上层模块也可见
    api(project(":workflow-core"))

    // Flyway 统一 DDL 迁移(建表由迁移脚本负责,不再用 hbm2ddl)
    implementation(project(":workflow-persistence-flyway"))

    // JPA 规范 + Hibernate 实现
    implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
    implementation("org.hibernate.orm:hibernate-core:6.4.4.Final")

    // H2 内存数据库(测试/演示用,生产替换为 MySQL/PostgreSQL)
    implementation("com.h2database:h2:2.2.224")

    // HikariCP 连接池
    implementation("com.zaxxer:HikariCP:5.1.0")

    // JSON 序列化(已在根项目 api 引入,这里可省略)
    // - fastjson2
    // - hutool

    // 日志实现 - 让 JPA 模块运行时能输出 SQL
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // 测试
    testImplementation(project(":workflow-tests"))
}