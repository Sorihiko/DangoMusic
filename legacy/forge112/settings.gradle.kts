pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
rootProject.name = "DangoMusic"

// 共用代码（core + ui）在 ../../common，作为组合构建引进来
includeBuild("../../common")


