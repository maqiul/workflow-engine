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

    // MyBatis(国产,苞米豆的基础) —— **api**：MybatisPersistence 的公开签名暴露
    // SqlSession / SqlSessionFactory（inSession 的 lambda 参数、sqlSessionFactory()），
    // 消费方编译期必须能看到这些类，挂在 implementation 上会报 "cannot find symbol"。
    // 版本 3.5.19 与 mybatis-plus 3.5.17 的传递依赖一致，不额外抬消费方的版。
    api("org.mybatis:mybatis:3.5.19")

    // MyBatis-Plus(苞米豆增强包) —— **compileOnly**（原为 api，2026-10-09 下调）：
    // 引擎只在仓储实现内部用它（BaseMapper / QueryWrapper / @TableField 等），
    // 公开签名里**没有**任何 com.baomidou 类型暴露给消费方。
    // 挂 api 会把 3.5.17 传递给消费方：消费方若自带其它版本（如 Spring Boot 生态的 3.5.7），
    // Gradle 的冲突解决默认「选最高版本」，会把消费方顶到 3.5.17，可能触发 NoSuchMethodError。
    // 改 compileOnly 后完全不传递 —— 与本模块 HikariCP / H2 的处理一致（宿主自管），
    // 消费方用自己的版本，引擎只在运行时用它的稳定 API。
    // 用到的 FieldStrategy.ALWAYS 自 3.5.7 起即存在（已用 javap 实测校验），版本安全。
    // ⚠️ 消费方使用本模块时**必须自行引入 mybatis-plus**（用 MyBatis 持久化实现却没装
    // MyBatis-Plus 本身就是缺依赖，运行时 NoClassDefFoundError 报错清晰）。
    compileOnly("com.baomidou:mybatis-plus:3.5.17")

    // H2 内存数据库(测试/演示用,生产替换为 MySQL/PostgreSQL)
    // compileOnly:引擎 main 代码里 H2 只以 JDBC URL / 驱动类名(字符串)出现,编译期无需真实依赖。
    // 不作运行时依赖传递 —— 嵌入式集成时宿主生产用 MySQL,多带一个 H2 只会污染 classpath
    // 并引入版本仲裁问题。
    compileOnly("com.h2database:h2:2.5.252")

    // HikariCP 连接池 —— 只有「引擎自建池」路径(init() / init(url,user,pass))需要它。
    // compileOnly(而非 implementation)的理由:嵌入式集成时宿主(Spring Boot 3.5)自管
    // HikariCP 6.x,引擎若把 5.1.0 作为运行时依赖传出去,Maven 的 nearest-wins 会让宿主
    // 用上低版本,与 Spring Boot 期望的 6.x 不符(NoSuchMethodError 风险)。
    // 走 withDataSource(宿主连接池)的集成方式完全不依赖本依赖。
    // 独立/演示用法请自行引入连接池,见 README「接入自己的数据源与事务」。
    compileOnly("com.zaxxer:HikariCP:7.1.0")

    // 日志实现 - 让 MyBatis 模块运行时能输出 SQL
    implementation("ch.qos.logback:logback-classic:1.6.5")

    // 测试
    testImplementation(project(":workflow-tests"))
    // mybatis-plus 已降为 compileOnly（不向消费方传递），本模块自身测试需显式补运行时依赖
    testRuntimeOnly("com.baomidou:mybatis-plus:3.5.17")
}
