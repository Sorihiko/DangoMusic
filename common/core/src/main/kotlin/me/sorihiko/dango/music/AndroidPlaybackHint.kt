package me.sorihiko.dango.music

import me.sorihiko.dango.i18n.I18n
import me.sorihiko.dango.platform.Platform
import java.io.File
import java.util.Locale

/** 只提示安卓玩家可选的音频参数，不改变现有输出选择；每次启动最多显示一次。 */
class AndroidPlaybackHint(
    private val android: Boolean = detectAndroid(),
    private val openAl: Boolean = java.lang.Boolean.getBoolean("dangomusic.openal"),
) {
    private var shown = false

    fun tick(platform: Platform) {
        if (!android || openAl || shown) return
        shown = platform.showChatMessage("[Dango Music] " + I18n.text(
            "Android detected. If music playback causes FPS drops, try appending -Ddangomusic.openal=true to your launcher's JVM arguments (separated from other arguments by a space), then restart the game."),
            "-Ddangomusic.openal=true", I18n.text("Click to copy JVM argument"))
    }

    companion object {
        /** Linux 桌面也可能装 Android SDK，不能只凭 Linux 或 ANDROID_HOME 就认定是安卓。 */
        fun isAndroid(osName: String, runtimeName: String, vmName: String, env: Map<String, String>, androidSystem: Boolean): Boolean {
            val os = osName.lowercase(Locale.ROOT)
            if (os.contains("android")) return true
            if (!os.contains("linux")) return false
            val runtime = (runtimeName + " " + vmName).lowercase(Locale.ROOT)
            return runtime.contains("android") || runtime.contains("dalvik") || androidSystem ||
                (env["ANDROID_ROOT"] == "/system" && env["ANDROID_DATA"] == "/data") ||
                !env["POJAV_NATIVEDIR"].isNullOrBlank()
        }

        private fun detectAndroid(): Boolean = try {
            val env = listOf("ANDROID_ROOT", "ANDROID_DATA", "POJAV_NATIVEDIR").mapNotNull { key ->
                System.getenv(key)?.let { key to it }
            }.toMap()
            isAndroid(System.getProperty("os.name", ""), System.getProperty("java.runtime.name", ""),
                System.getProperty("java.vm.name", ""), env,
                listOf("/system/build.prop", "/system/bin/app_process", "/system/bin/app_process64").any { File(it).isFile })
        } catch (_: SecurityException) { false }
    }
}
