// workflow-sample: 示例 Demo
plugins {
    application
}

application {
    mainClass.set("com.workflow.sample.LeaveDemo")
}

dependencies {
    implementation(project(":workflow-core"))
    // 日志实现 - sample 模块需要能看日志
    implementation("ch.qos.logback:logback-classic:1.5.6")
}