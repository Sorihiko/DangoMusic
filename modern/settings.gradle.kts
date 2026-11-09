pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "DangoMusic-modern"

// 共用代码（core + ui）在 ../common，作为组合构建引进来
includeBuild("../common")

// 每个"Minecraft 版本 + 加载器"一个子项目，配置在 versions/<名字>/gradle.properties
// 加新版本：在 versions/ 里加一个文件夹，再把名字加到下面这行
val targets = listOf("1.20.1-fabric", "1.20.1-forge", "1.21.1-fabric", "1.21.1-neoforge",
    "1.21.10-fabric", "1.21.10-neoforge", "1.21.11-fabric", "1.21.11-neoforge")
targets.forEach {
    include(it)
    project(":$it").projectDir = file("versions/$it")
}
