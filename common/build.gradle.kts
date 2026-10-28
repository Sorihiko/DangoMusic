import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

import java.util.Properties

plugins {
    // 版本在 settings.gradle.kts 里按 Gradle 版本选
    kotlin("jvm") apply false
}

val modVersion: String = Properties().apply {
    rootDir.resolve("../version.properties").inputStream().use { load(it) }
}.getProperty("version")

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "me.sorihiko.dango"
    version = modVersion

    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net")
    }

    // 编译成 Java 8 字节码：1.8.9 跑在 Java 8 上，高版本的 Java 也能直接用。
    // 不用 toolchain：legacy 用 JDK 8 构建、modern 用 JDK 21 构建，都直接用当前这个 JDK
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        if (JavaVersion.current().isJava9Compatible) {
            options.release.set(8)
        } else {
            sourceCompatibility = "1.8"
            targetCompatibility = "1.8"
        }
    }

    dependencies {
        // 游戏本身带了这两个库（1.8.9 起每个版本都有），不打进 jar
        "compileOnly"("com.google.code.gson:gson:2.2.4")
        "compileOnly"("org.apache.logging.log4j:log4j-api:2.0-beta9")
    }
}
