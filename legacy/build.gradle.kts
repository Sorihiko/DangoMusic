import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.minecraftforge.gradle.user.patcherUser.forge.ForgeExtension

import java.util.Properties

buildscript {
    repositories {
        maven("https://files.minecraftforge.net/maven")
        mavenCentral()
        mavenLocal()
    }
    dependencies {
        classpath("net.minecraftforge.gradle:ForgeGradle-Modern:2.1")
    }
}

plugins {
    kotlin("jvm") version "2.0.21"
    id("com.github.johnrengelman.shadow") version "7.1.1"
}

apply(plugin = "net.minecraftforge.gradle.forge")

// 版本号统一写在根目录的 version.properties
val modVersion: String = Properties().apply {
    rootDir.resolve("../version.properties").inputStream().use { load(it) }
}.getProperty("version")

group = "me.sorihiko.dango"
version = modVersion

repositories {
    mavenCentral()
    mavenLocal()
}

configure<ForgeExtension> {
    version = "1.8.9-11.15.1.2318-1.8.9"
    runDir = "run"
    mappings = "stable_22"
    makeObfSourceJar = false
}

// 要打进 jar 的依赖：共用代码（common/core、common/ui，见 settings.gradle.kts 里的 includeBuild）
// 以及它们用到的 Kotlin 运行库、MP3 解码、二维码
val shade by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
configurations["compileOnly"].extendsFrom(shade)
// runClient 开发环境里也要能找到这些依赖
configurations["runtimeOnly"].extendsFrom(shade)

dependencies {
    shade("me.sorihiko.dango:core:$modVersion")
    shade("me.sorihiko.dango:ui:$modVersion")
    shade(kotlin("stdlib"))
    shade("javazoom:jlayer:1.0.1")
    shade("org.jcraft:jorbis:0.0.17")
    shade("com.google.zxing:core:3.3.3")
}

kotlin {
    jvmToolchain(8)
}

java {
    sourceCompatibility = null
    targetCompatibility = null
}

tasks.processResources {
    val forgeExtension = project.extensions.getByName<ForgeExtension>("minecraft")
    inputs.property("version", project.version)
    inputs.property("mcversion", forgeExtension.version)
    filesMatching(listOf("mcmod.info", "version.properties", "dangomusic-build.properties")) {
        expand(mapOf("version" to project.version, "mcversion" to forgeExtension.version, "target" to "1.8.9"))
    }
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(shade)
    archiveBaseName.set("DangoMusic")
    archiveClassifier.set("")
    archiveVersion.set("$modVersion+1.8.9")     // DangoMusic-1.1.0+1.8.9.jar
    // 依赖全部改包名，避免和其它 mod / Lunar 自带的同名库冲突
    relocate("kotlin", "me.sorihiko.dango.shadow.kotlin")
    relocate("org.intellij.lang.annotations", "me.sorihiko.dango.shadow.annotations.intellij")
    relocate("org.jetbrains.annotations", "me.sorihiko.dango.shadow.annotations.jetbrains")
    relocate("javazoom", "me.sorihiko.dango.shadow.javazoom")
    relocate("com.jcraft", "me.sorihiko.dango.shadow.jcraft")
    relocate("com.google.zxing", "me.sorihiko.dango.shadow.zxing")
    // Java 9+ 的模块描述文件，Forge 1.8.9 的老 ASM 读不了
    exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module")
}

// 普通 Forge / Lunar 运行时用的是混淆名（func_xxx），打包后要把 MCP 名转换回去（reobf）
@Suppress("UNCHECKED_CAST")
(extensions.getByName("reobf") as NamedDomainObjectContainer<Any>).create("shadowJar")

afterEvaluate {
    tasks.named("reobfShadowJar") { dependsOn(shadowJar) }
    tasks.named("build") { dependsOn("reobfShadowJar") }
    tasks.named("assemble") { dependsOn("reobfShadowJar") }
}

// 不用 Forge 默认的 jar（没有打依赖进去），只发布 shadowJar
tasks.named("reobfJar").configure { enabled = false }
