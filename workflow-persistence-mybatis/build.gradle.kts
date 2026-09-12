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
    // compileOnly:引擎 main 代码里 H2 只以 JDBC URL / 驱动类名(字符串)出现,编译期无需真实依赖。
    // 不作运行时依赖传递 —— 嵌入式集成时宿主生产用 MySQL,多带一个 H2 只会污染 classpath
    // 并引入版本仲裁问题。
    compileOnly("com.h2database:h2:2.2.224")

    // HikariCP 连接池 —— 只有「引擎自建池」路径(init() / init(url,user,pass))需要它。
    // compileOnly(而非 implementation)的理由:嵌入式集成时宿主(Spring Boot 3.5)自管
    // HikariCP 6.x,引擎若把 5.1.0 作为运行时依赖传出去,Maven 的 nearest-wins 会让宿主
    // 用上低版本,与 Spring Boot 期望的 6.x 不符(NoSuchMethodError 风险)。
    // 走 withDataSource(宿主连接池)的集成方式完全不依赖本依赖。
    // 独立/演示用法请自行引入连接池,见 README「接入自己的数据源与事务」。
    compileOnly("com.zaxxer:HikariCP:5.1.0")

    // 日志实现 - 让 MyBatis 模块运行时能输出 SQL
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // 测试
    testImplementation(project(":workflow-tests"))
}
