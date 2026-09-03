// 根项目统一配置
plugins {
    java
    application
}

group = "com.workflow"
version = "1.0.0"

// 统一依赖版本
val fastjson2Version = "2.0.49"
val hutoolVersion = "5.8.27"
val slf4jVersion = "2.0.13"
val logbackVersion = "1.5.6"
val junitVersion = "5.10.2"
val assertjVersion = "3.25.3"

allprojects {
    group = "com.workflow"
    version = "1.0.0"

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
}