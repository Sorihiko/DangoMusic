import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

import java.util.Properties

/*
 * 所有高版本（versions/ 里的每个子项目）共用的构建配置。
 *
 *   src/main/          所有版本、所有加载器共用的代码：画图、按键、播放器界面、着色器
 *   src/mc<版本>/      某个 Minecraft 版本专用的几处写法（McCompat.kt）
 *   versions/<名字>/   加载器入口（Fabric / Forge / NeoForge）和 mod 信息文件
 */
plugins {
    id("dev.architectury.loom") version "1.13-SNAPSHOT" apply false
    kotlin("jvm") version "2.2.21" apply false
    id("com.gradleup.shadow") version "9.2.2" apply false
}

// 版本号统一写在根目录的 version.properties
val modVersion: String = Properties().apply {
    rootDir.resolve("../version.properties").inputStream().use { load(it) }
}.getProperty("version")

subprojects {
    apply(plugin = "dev.architectury.loom")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")

    val loader = property("loom.platform") as String               // fabric / forge / neoforge
    val mcVersion = property("minecraft_version") as String
    val mcRange = property("minecraft_range") as String
    val compat = property("compat") as String                      // 用 src/mc<compat>/ 里的写法
    val javaVersion = (property("java_version") as String).toInt()
    val loaderDeps: List<Pair<String, String>> = when (loader) {
        "fabric" -> listOf(
            "modImplementation" to "net.fabricmc:fabric-loader:${property("fabric_loader_version")}",
            "modImplementation" to "net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}",
        )
        "forge" -> listOf("forge" to "net.minecraftforge:forge:${property("forge_version")}")
        "neoforge" -> listOf("neoForge" to "net.neoforged:neoforge:${property("neoforge_version")}")
        else -> error("Unknown loader: $loader")
    }

    group = "me.sorihiko.dango"
    version = modVersion
    extensions.configure<BasePluginExtension> { archivesName.set("DangoMusic") }

    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForge" }
    }

    // 共用代码 + 这个 Minecraft 版本专用的写法
    val shared = rootProject.file("src")
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

    // 要打进 jar 的依赖：共用代码（common/core、common/ui）以及它们用到的 Kotlin 运行库、MP3 解码、二维码
    val shade = configurations.create("shade") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    configurations.getByName("implementation").extendsFrom(shade)

    val loom = extensions.getByType<LoomGradleExtensionAPI>()
    dependencies {
        "minecraft"("com.mojang:minecraft:$mcVersion")
        "mappings"(loom.officialMojangMappings())
        loaderDeps.forEach { (configuration, notation) -> configuration(notation) }

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
        filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml", "dangomusic-build.properties")) {
            expand(props)
        }
    }

    // 先把依赖打进去、改包名（避免和其它 mod 冲突），再交给 Loom 转成运行时用的名字
    val shadowJar = tasks.named<ShadowJar>("shadowJar") {
        configurations.set(listOf(shade))
        archiveClassifier.set("dev-shadow")
        relocate("kotlin", "me.sorihiko.dango.shadow.kotlin")
        relocate("org.intellij.lang.annotations", "me.sorihiko.dango.shadow.annotations.intellij")
        relocate("org.jetbrains.annotations", "me.sorihiko.dango.shadow.annotations.jetbrains")
        relocate("javazoom", "me.sorihiko.dango.shadow.javazoom")
        relocate("com.jcraft", "me.sorihiko.dango.shadow.jcraft")
        relocate("com.google.zxing", "me.sorihiko.dango.shadow.zxing")
        exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module")
        // META-INF/versions 已经去掉了，就不能再声明 Multi-Release，
        // 否则 Forge 1.20.1 加载 jar 时找不到这个目录直接崩溃（Shadow 9 默认会加这一行）
        addMultiReleaseAttribute.set(false)
    }

    tasks.named<RemapJarTask>("remapJar") {
        inputFile.set(shadowJar.flatMap { it.archiveFile })
        archiveVersion.set("$modVersion+$mcVersion")
        archiveClassifier.set(loader)                 // DangoMusic-1.1.0+1.21.1-neoforge.jar
    }
}
