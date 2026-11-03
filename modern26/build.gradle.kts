import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.neoforged.moddevgradle.dsl.NeoForgeExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

import java.util.Properties

/*
 * 26.x 的构建配置。Minecraft 从 26.1 起不再混淆，不需要映射表和重映射：
 * 编译好的 jar（打进依赖、改好包名之后）就是最终的 mod。
 *
 *   ../modern/src/main/     所有高版本共用的代码（和 1.20.1～1.21.11 同一份）
 *   ../modern/src/mc<版本>/ 某个 Minecraft 版本专用的写法（McCompat.kt）
 *   versions/<名字>/        加载器入口和 mod 信息文件
 */
plugins {
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT" apply false
    id("net.neoforged.moddev") version "2.0.148" apply false
    kotlin("jvm") version "2.3.21" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
}

val modVersion: String = Properties().apply {
    rootDir.resolve("../version.properties").inputStream().use { load(it) }
}.getProperty("version")

subprojects {
    val loader = property("loader") as String                       // fabric / neoforge
    val mcVersion = property("minecraft_version") as String
    val mcRange = property("minecraft_range") as String
    val compat = property("compat") as String
    val javaVersion = (property("java_version") as String).toInt()

    when (loader) {
        "fabric" -> apply(plugin = "net.fabricmc.fabric-loom")
        "neoforge" -> apply(plugin = "net.neoforged.moddev")
        else -> error("Unknown loader: $loader")
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")

    group = "me.sorihiko.dango"
    version = modVersion
    extensions.configure<BasePluginExtension> { archivesName.set("DangoMusic") }

    repositories {
        mavenCentral()
    }

    val shared = rootProject.file("../modern/src")
    extensions.configure<KotlinJvmProjectExtension> {
        sourceSets.getByName("main").kotlin.srcDir(shared.resolve("main/kotlin"))
        sourceSets.getByName("main").kotlin.srcDir(shared.resolve("mc$compat/kotlin"))
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) }
    }
    extensions.configure<SourceSetContainer> {
        getByName("main").resources.srcDir(shared.resolve("main/resources"))
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.toVersion(javaVersion)
        targetCompatibility = JavaVersion.toVersion(javaVersion)
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaVersion)
        options.encoding = "UTF-8"
    }

    val shade = configurations.create("shade") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    configurations.getByName("implementation").extendsFrom(shade)

    if (loader == "neoforge") {
        extensions.configure<NeoForgeExtension> {
            version = property("neoforge_version") as String
        }
    }

    dependencies {
        if (loader == "fabric") {
            "minecraft"("com.mojang:minecraft:$mcVersion")
            "implementation"("net.fabricmc:fabric-loader:${property("fabric_loader_version")}")
            "implementation"("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
        }
        "shade"("me.sorihiko.dango:core:$modVersion")
        "shade"("me.sorihiko.dango:ui:$modVersion")
        "shade"(kotlin("stdlib"))
        "shade"("javazoom:jlayer:1.0.1")
        "shade"("org.jcraft:jorbis:0.0.17")
        "shade"("com.google.zxing:core:3.3.3")
    }

    tasks.named<ProcessResources>("processResources") {
        val props = mapOf(
            "version" to modVersion,
            "minecraft_range" to mcRange,
            "java_version" to javaVersion.toString(),
            "target" to "$mcVersion-$loader",
        )
        inputs.properties(props)
        filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "dangomusic-build.properties")) {
            expand(props)
        }
    }

    // 普通 jar 只是中间产物，改个名字免得和最终的混在一起
    tasks.named<Jar>("jar") { archiveClassifier.set("dev") }

    // 最终的 mod：把依赖打进去、改包名（避免和其它 mod 冲突）。不再需要重映射
    val shadowJar = tasks.named<ShadowJar>("shadowJar") {
        configurations.set(listOf(shade))
        archiveVersion.set("$modVersion+$mcVersion")
        archiveClassifier.set(loader)               // DangoMusic-1.3.1+26.2-fabric.jar
        relocate("kotlin", "me.sorihiko.dango.shadow.kotlin")
        relocate("org.intellij.lang.annotations", "me.sorihiko.dango.shadow.annotations.intellij")
        relocate("org.jetbrains.annotations", "me.sorihiko.dango.shadow.annotations.jetbrains")
        relocate("javazoom", "me.sorihiko.dango.shadow.javazoom")
        relocate("com.jcraft", "me.sorihiko.dango.shadow.jcraft")
        relocate("com.google.zxing", "me.sorihiko.dango.shadow.zxing")
        exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module")
        addMultiReleaseAttribute.set(false)
    }
    tasks.named("build") { dependsOn(shadowJar) }
}
