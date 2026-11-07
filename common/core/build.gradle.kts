// 核心：事件、模块、设置、网易云接口、播放器、一起听歌。不碰任何绘图。
dependencies {
    "implementation"("org.jcraft:jorbis:0.0.17")     // Ogg Vorbis 解码
    "implementation"("javazoom:jlayer:1.0.1")       // MP3 解码
    "implementation"("com.google.zxing:core:3.3.3") // 登录二维码
}

// Windows x64 builds can bundle the SMTC helper. Without Cargo the shared JVM build still works.
val windowsX64 = System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
    System.getProperty("os.arch") in setOf("amd64", "x86_64")
val cargo = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
    .map { File(it.trim('"'), "cargo.exe") }.firstOrNull { it.isFile }
val buildSmtc = windowsX64 && cargo != null &&
    findProperty("dangoWindowsSmtc")?.toString() != "false"
if (buildSmtc) {
    val source = rootProject.file("../native/windows-smtc")
    val target = layout.buildDirectory.dir("windows-smtc")
    val binary = target.map { it.file("x86_64-pc-windows-msvc/release/DangoMusicMedia.exe") }
    val buildWindowsSmtc = tasks.register<Exec>("buildWindowsSmtc") {
        workingDir(source)
        commandLine(cargo!!.absolutePath, "build", "--release", "--locked", "--target", "x86_64-pc-windows-msvc",
            "--target-dir", target.get().asFile.absolutePath)
        inputs.files(source.resolve("Cargo.toml"), source.resolve("Cargo.lock"), source.resolve(".cargo/config.toml"))
        inputs.dir(source.resolve("src"))
        outputs.file(binary)
    }
    tasks.named<ProcessResources>("processResources") {
        dependsOn(buildWindowsSmtc)
        from(binary) { into("native/windows-x64") }
    }
}
