package me.sorihiko.dango.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.event.EventHandler
import me.sorihiko.dango.event.Listener
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.module.Module
import me.sorihiko.dango.module.ModuleManager
import me.sorihiko.dango.module.values.BooleanValue
import me.sorihiko.dango.module.values.ColorValue
import me.sorihiko.dango.module.values.TextValue
import me.sorihiko.dango.module.values.FloatValue
import me.sorihiko.dango.module.values.IntValue
import me.sorihiko.dango.module.values.ModuleValue

import java.lang.reflect.Field
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 把每个模块的开关状态和 @ModuleValue 设置存到 config/dangomusic/config.json，启动时自动读回。
 *
 * - 设置值在启动时立刻读回；
 * - 模块开关要等着色器、字体都加载完（第 3 个 tick）再恢复，避免 onEnable 时资源还没准备好；
 * - 之后每 2 秒检查一次，有变化才写文件；游戏关闭时再存一次。
 */
class ConfigManager(
    private val moduleManager: ModuleManager,
    private val file: Path,
) : Listener {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var pendingEnabled: Map<Module, Boolean>? = null
    private var ticks = 0
    private var ready = false
    private var lastSaved: String? = null

    private fun modules(): List<Module> = moduleManager.modules.values.flatten()

    private fun valueFields(module: Module): List<Field> =
        module.javaClass.declaredFields.filter {
            it.isAnnotationPresent(ModuleValue::class.java)
        }.onEach { it.isAccessible = true }

    // ---------------------------------------------------------------- 读取

    /** 第一次运行（没有配置文件）：所有功能默认打开 */
    private var fresh = false

    fun load() {
        if (!Files.exists(file)) {
            fresh = true
            DangoLogger.info("No config found, using defaults: {}", file.toString())
            return
        }
        try {
            val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            val root = JsonParser().parse(text).asJsonObject
            val enabledMap = mutableMapOf<Module, Boolean>()

            for (module in modules()) {
                val obj = root.get(module.name) as? JsonObject ?: continue
                (obj.get("enabled") as? JsonPrimitive)?.let { enabledMap[module] = it.asBoolean }

                val values = obj.get("values") as? JsonObject ?: continue
                for (field in valueFields(module)) {
                    val json = values.get(field.name) ?: continue
                    try {
                        applyValue(field.get(module), json)
                    } catch (e: Exception) {
                        DangoLogger.warn("Config value skipped: {}.{}", module.name, field.name)
                    }
                }
            }
            pendingEnabled = enabledMap
            DangoLogger.info("Config loaded: {}", file.toString())
        } catch (e: Exception) {
            DangoLogger.error("Config load failed, using defaults. {}", e)
        }
    }

    private fun applyValue(value: Any?, json: JsonElement) {
        when (value) {
            is BooleanValue -> value.set(json.asBoolean)
            is IntValue -> value.setValue(json.asInt)
            is FloatValue -> value.setValue(json.asFloat)
            is ColorValue -> if (json.isJsonPrimitive) ColorValue.parse(json.asString)?.let { value.setValue(it) }
            is TextValue -> if (json.isJsonPrimitive) value.value = json.asString
        }
    }

    // ---------------------------------------------------------------- 保存

    private fun serialize(): String {
        val root = JsonObject()
        for (module in modules()) {
            val obj = JsonObject()
            obj.addProperty("enabled", module.enabled)
            val values = JsonObject()
            for (field in valueFields(module)) {
                when (val v = field.get(module)) {
                    is BooleanValue -> values.addProperty(field.name, v.get())
                    is IntValue -> values.addProperty(field.name, v.value)
                    is FloatValue -> values.addProperty(field.name, v.value)
                    is ColorValue -> values.addProperty(field.name, v.hex())
                    is TextValue -> values.addProperty(field.name, v.value)
                }
            }
            obj.add("values", values)
            root.add(module.name, obj)
        }
        return gson.toJson(root)
    }

    @Synchronized
    fun saveIfChanged() {
        if (!ready) return
        try {
            val text = serialize()
            if (text == lastSaved) return
            Files.createDirectories(file.parent)
            // 先写临时文件再替换，避免写到一半崩溃把配置弄坏
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            lastSaved = text
        } catch (e: Exception) {
            DangoLogger.error("Config save failed. {}", e)
        }
    }

    // ---------------------------------------------------------------- 时机

    @EventHandler
    fun onTick(e: TickEvent) {
        ticks++
        if (!ready && ticks >= 3) {
            pendingEnabled?.forEach { (module, enabled) ->
                try {
                    module.enabled = enabled
                } catch (ex: Exception) {
                    DangoLogger.warn("Restore module state failed: {}", module.name)
                }
            }
            pendingEnabled = null
            if (fresh) {
                for (module in modules()) {
                    try {
                        module.enabled = module.enabledByDefault
                    } catch (ex: Exception) {
                        DangoLogger.warn("Enable module failed: {}", module.name)
                    }
                }
                fresh = false
            }
            ready = true
            // 也保存首次推断的语言和新增选项，避免下次启动时重新采用游戏语言。
            saveIfChanged()
            return
        }
        if (ready && ticks % 40 == 0) saveIfChanged()
    }

    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread({ saveIfChanged() }, "Dango-ConfigSave"))
    }
}
