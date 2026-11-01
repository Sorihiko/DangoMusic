package me.sorihiko.dango.music

import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import java.io.File

/**
 * 玩家自己的歌词字体：把 .ttf / .otf 放进 config/dangomusic/fonts/，在设置里选一个就能用（不用重启）。
 * 只是列文件名，真正加载字体在渲染那边（CjkFont）。
 */
object LyricFonts {
    val folder: File get() = DangoMusic.instance.clientDir.resolve("fonts").toFile()

    private var cached = emptyList<String>()
    private var scannedAt = 0L

    /** 文件夹里的字体文件名（按名字排序）；两秒内重复调用直接用上次的结果 */
    fun list(): List<String> {
        val now = System.currentTimeMillis()
        if (now - scannedAt > 2000) {
            scannedAt = now
            // 第一次用时把文件夹建好，玩家才找得到往哪放
            if (!folder.exists()) ensureFolder()
            cached = try {
                folder.listFiles()?.filter { f ->
                    val n = f.name.lowercase()
                    f.isFile && (n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc"))
                }?.map { it.name }?.sortedBy { it.lowercase() } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        return cached
    }

    /** 选中的字体文件；空 = 默认字体，文件不在了也当默认 */
    fun file(name: String): File? = if (name.isEmpty() || name !in list()) null else File(folder, name)

    /** 设置里显示的名字：去掉扩展名 */
    fun label(name: String): String = name.substringBeforeLast('.')

    fun ensureFolder() {
        try { folder.mkdirs() } catch (_: Exception) { }
    }

    /**
     * 用系统的文件管理器打开字体文件夹；打不开（安卓启动器里没有文件管理器命令）返回 false，让界面把路径告诉玩家。
     * 游戏里 Java 的桌面功能是关着的（headless），所以和原版打开资源包文件夹一样，直接调系统命令
     */
    fun openFolder(): Boolean {
        ensureFolder()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            "win" in os -> listOf("explorer.exe", folder.absolutePath)
            "mac" in os -> listOf("open", folder.absolutePath)
            else -> listOf("xdg-open", folder.absolutePath)
        }
        return try {
            ProcessBuilder(command).start()
            true
        } catch (e: Exception) {
            DangoLogger.warn("Could not open the fonts folder {}: {}", folder, e.toString())
            false
        }
    }
}
