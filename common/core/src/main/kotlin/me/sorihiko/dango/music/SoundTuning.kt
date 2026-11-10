package me.sorihiko.dango.music

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * 变速变调和均衡器的设置：一份全局的，加上"按歌记住"的。
 * 当前这首记住过就用它自己的（改的也是它自己的），没记住就用全局的。
 * 存在 config 目录的 sound_tuning.json 里。
 */
object SoundTuning {

    /**
     * [speed] 0.5..2；[semitones] -12..12；[linked] = 音调跟着速度走（像黑胶变速），这时 semitones 不用。
     * [eqOn] 均衡器开关；[eq] 10 段增益（dB，-12..12，关着时也留着）
     */
    class Setting(
        val speed: Float = 1f, val semitones: Float = 0f, val linked: Boolean = false,
        val eqOn: Boolean = false, val eq: FloatArray = FloatArray(Equalizer.BANDS),
    ) {
        /** 变速变调是不是默认值（按钮亮不亮看这个） */
        val isDefault: Boolean get() = abs(speed - 1f) < 0.001f && (linked || abs(semitones) < 0.001f)
        /** 均衡器真的在起作用 */
        val eqActive: Boolean get() = eqOn && eq.any { abs(it) > 0.01f }
        fun with(
            speed: Float = this.speed, semitones: Float = this.semitones, linked: Boolean = this.linked,
            eqOn: Boolean = this.eqOn, eq: FloatArray = this.eq,
        ) = Setting(clampSpeed(speed), clampSemitones(semitones), linked, eqOn, FloatArray(Equalizer.BANDS) { clampGain(eq.getOrElse(it) { 0f }) })
    }

    /** 实际交给解码线程的：速度倍数、频率倍数、均衡器增益（null = 不用） */
    class Effective(val speed: Float, val pitch: Float, val eq: FloatArray?) {
        val bypass: Boolean get() = abs(speed - 1f) < 0.001f && abs(pitch - 1f) < 0.001f
    }

    const val MIN_SPEED = 0.5f
    const val MAX_SPEED = 2f
    const val MAX_SEMITONES = 12f

    fun clampSpeed(v: Float) = (Math.round(v.coerceIn(MIN_SPEED, MAX_SPEED) * 20f) / 20f)
    fun clampSemitones(v: Float) = Math.round(v.coerceIn(-MAX_SEMITONES, MAX_SEMITONES)).toFloat()
    /** 均衡器每段：-12..12 dB，0.5 一档 */
    fun clampGain(v: Float) = Math.round(v.coerceIn(-Equalizer.MAX_GAIN, Equalizer.MAX_GAIN) * 2f) / 2f

    @Volatile private var global = Setting()
    /** 记住的歌（按最近改的顺序，太多了扔最早的） */
    private val tracks = LinkedHashMap<String, Setting>()
    @Volatile private var loaded = false

    /** 一起听时速度锁在 1x（不然和别人对不上），音调照常 */
    val speedLocked: Boolean get() = me.sorihiko.dango.party.ListenParty.role != me.sorihiko.dango.party.ListenParty.Role.NONE

    fun get(key: String?): Setting {
        load()
        if (key != null) synchronized(tracks) { tracks[key] }?.let { return it }
        return global
    }

    fun isRemembered(key: String?): Boolean {
        load()
        return key != null && synchronized(tracks) { tracks.containsKey(key) }
    }

    /** 改设置：这首记住过就改它自己的，不然改全局的 */
    fun update(key: String?, s: Setting) {
        load()
        val own = key != null && synchronized(tracks) {
            if (tracks.containsKey(key)) { tracks.remove(key); tracks[key] = s; true } else false
        }
        if (!own) global = s
        save()
    }

    /** 打开 = 把现在的设置记到这首歌上；关掉 = 这首回到全局设置 */
    fun setRemembered(key: String, on: Boolean) {
        load()
        synchronized(tracks) {
            if (on) {
                tracks[key] = tracks[key] ?: global
                while (tracks.size > MAX_TRACKS) tracks.remove(tracks.keys.first())
            } else tracks.remove(key)
        }
        save()
    }

    fun effective(key: String?): Effective {
        val s = get(key)
        val speed = if (speedLocked) 1f else s.speed
        val pitch = if (s.linked) speed else 2f.pow(s.semitones / 12f)
        return Effective(speed, pitch, if (s.eqActive) s.eq else null)
    }

    /** 音调跟着速度时，显示用的半音数 */
    fun linkedSemitones(speed: Float): Float = (12.0 * Math.log(speed.toDouble()) / Math.log(2.0)).toFloat()

    // ---------------------------------------------------------------- 存取

    private const val MAX_TRACKS = 2000
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Dango-SoundTuning").apply { isDaemon = true } }
    @Volatile private var pendingSave = false

    private fun file() = DangoMusic.instance.clientDir.resolve("sound_tuning.json").toFile()

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val f = file()
                loaded = true
                if (!f.isFile) return
                val root = JsonParser().parse(f.readText(Charsets.UTF_8)).asJsonObject
                global = parse(root)
                val t = root.get("tracks")
                if (t != null && t.isJsonObject) synchronized(tracks) {
                    for ((k, v) in t.asJsonObject.entrySet()) if (v.isJsonObject) tracks[k] = parse(v.asJsonObject)
                }
            } catch (e: Exception) {
                // DangoMusic 还没初始化好时拿不到目录，下次再读
                if (e is UninitializedPropertyAccessException || e is NullPointerException) loaded = false
                else DangoLogger.warn("Sound tuning: could not read settings: {}", e.toString())
            }
        }
    }

    private fun parse(o: JsonObject) = Setting(
        clampSpeed(num(o, "speed", 1f)),
        clampSemitones(num(o, "semitones", 0f)),
        o.get("linked")?.let { it.isJsonPrimitive && it.asBoolean } ?: false,
        o.get("eqOn")?.let { it.isJsonPrimitive && it.asBoolean } ?: false,
        FloatArray(Equalizer.BANDS) { i ->
            val a = o.get("eq")
            if (a != null && a.isJsonArray && i < a.asJsonArray.size()) clampGain(try { a.asJsonArray[i].asFloat } catch (_: Exception) { 0f }) else 0f
        },
    )

    private fun num(o: JsonObject, k: String, def: Float): Float =
        try { o.get(k)?.asFloat ?: def } catch (_: Exception) { def }

    private fun put(o: JsonObject, s: Setting) {
        o.addProperty("speed", s.speed)
        o.addProperty("semitones", s.semitones)
        o.addProperty("linked", s.linked)
        o.addProperty("eqOn", s.eqOn)
        o.add("eq", com.google.gson.JsonArray().also { a -> for (g in s.eq) a.add(com.google.gson.JsonPrimitive(g)) })
    }

    /** 拖滑条时一直在改，攒半秒写一次 */
    private fun save() {
        if (pendingSave) return
        pendingSave = true
        writer.schedule({
            pendingSave = false
            try {
                val root = JsonObject()
                put(root, global)
                val t = JsonObject()
                synchronized(tracks) { for ((k, v) in tracks) t.add(k, JsonObject().also { put(it, v) }) }
                root.add("tracks", t)
                val f = file()
                val tmp = java.io.File(f.path + ".tmp")
                tmp.writeText(root.toString(), Charsets.UTF_8)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (e: Exception) {
                DangoLogger.warn("Sound tuning: could not save settings: {}", e.toString())
            }
        }, 500, TimeUnit.MILLISECONDS)
    }
}
