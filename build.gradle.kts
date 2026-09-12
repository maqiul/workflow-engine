import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

// 根项目统一配置
plugins {
    java
    application
}

// 版本号唯一来源：gradle.properties 的 projectVersion。
// 此处曾硬编码 "3.8.0"，而项目实际已到 3.17.0 —— 漂移 9 个版本无人发现，
// 因为没有任何机制把它和 CHANGELOG / 发布坐标绑在一起。
val projectVersion: String by project

group = "com.workflow"
version = projectVersion

// 统一依赖版本
val fastjson2Version = "2.0.49"
val hutoolVersion = "5.8.27"
val slf4jVersion = "2.0.13"
val logbackVersion = "1.5.6"
val junitVersion = "5.10.2"
val assertjVersion = "3.25.3"

// 对外发布的「库」模块。sample 是 Demo、tests 是测试模块，都不发布。
val publishableProjectNames = setOf(
    "workflow-core",
    "workflow-persistence-flyway",
    "workflow-persistence-jpa",
    "workflow-persistence-mybatis",
    "workflow-rest",
)

allprojects {
    group = "com.workflow"
    version = projectVersion

    repositories {
        // 优先使用国内镜像，避免 Maven Central 拉取慢
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    // 公共基础库 - 所有子模块都可用
    dependencies {
        "implementation"("com.alibaba.fastjson2:fastjson2:$fastjson2Version")
        "implementation"("org.slf4j:slf4j-api:$slf4jVersion")
        "implementation"("cn.hutool:hutool-all:$hutoolVersion")
        "testImplementation"("org.junit.jupiter:junit-jupiter:$junitVersion")
        "testImplementation"("org.assertj:assertj-core:$assertjVersion")
    }

    // ============ 发布：让文档里的坐标真正可解析 ============
    //
    // 此前 README / FEATURES 写着 `implementation("com.workflow:workflow-core:3.16.0")`，
    // 但构建里根本没有 maven-publish —— 那个坐标无处可解析，照文档做第一行就卡住。
    // 这里把「库」模块真正发布出来，让承诺成立。
    if (name in publishableProjectNames) {
        apply(plugin = "maven-publish")

        // 捕获当前子项目：内层 receiver 是 PublishingExtension，拿不到 components
        val module = this
        val moduleName = name

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(module.components["java"])
                    pom {
                        name.set(moduleName)
                        description.set("自研工作流引擎（零第三方工作流框架）· $moduleName")
                        url.set("https://github.com/maqiul/workflow-engine")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0")
                            }
                        }
                    }
                }
            }
            // 默认产出到 build/local-repo：既能直接验证产物，也便于离线消费。
            // 要发 Maven Central / 私有 Nexus，在此追加 maven { url = uri(...) } 即可；
            // 另外 maven-publish 内置的 publishToMavenLocal 始终可用。
            repositories {
                maven {
                    name = "localStaging"
                    url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
                }
            }
        }
    }
}