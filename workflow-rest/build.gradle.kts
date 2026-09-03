// workflow-rest: 零额外依赖的 REST 层
//
// 刻意不引入 Spring Boot —— 引擎定位是「嵌入式 jar」，
// 把整个 Spring 栈塞进依赖会逼所有调用方接受它。
// HTTP 用 JDK 自带的 com.sun.net.httpserver.HttpServer，
// JSON 用项目已有的 fastjson2，因此本模块除 workflow-core 外无任何第三方依赖。
//
// 需要 Spring 的调用方应当自己写一层薄 Controller 包装 IWorkflowEngine，
// 而不是让 core 反向依赖 web。
plugins {
    `java-library`
}

dependencies {
    api(project(":workflow-core"))
}
