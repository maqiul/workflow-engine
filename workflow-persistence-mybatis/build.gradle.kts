// workflow-persistence-mybatis: MyBatis-Plus 持久化实现(国产 ORM)
plugins {
    `java-library`
    application
}

application {
    mainClass.set("com.workflow.persistence.mybatis.MybatisPersistence")
}

dependencies {
    // 引擎核心接口 - 用 api 配置让上层模块也可见
    api(project(":workflow-core"))

    // Flyway 统一 DDL 迁移(建表由迁移脚本负责,不再内嵌 DDL)
    implementation(project(":workflow-persistence-flyway"))

    // MyBatis-Plus(国产,苞米豆) - 核心 + 扩展,不引 Spring
    // 必须用 api：MybatisPersistence 的公开签名暴露 SqlSession / SqlSessionFactory /
    // DataSource（inSession 的 lambda 参数、sqlSessionFactory()）。
    // 挂在 implementation 上会让消费方编译期拿不到这些类。
    api("com.baomidou:mybatis-plus:3.5.17")

    // H2 内存数据库(测试/演示用,生产替换为 MySQL/PostgreSQL)
    implementation("com.h2database:h2:2.2.224")

    // HikariCP 连接池
    implementation("com.zaxxer:HikariCP:5.1.0")

    // 日志实现 - 让 MyBatis 模块运行时能输出 SQL
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // 测试
    testImplementation(project(":workflow-tests"))
}
