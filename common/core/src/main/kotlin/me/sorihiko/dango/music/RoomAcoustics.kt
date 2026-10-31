package me.sorihiko.dango.music

import me.sorihiko.dango.platform.Platform
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 玩家所在空间的声学：从眼睛往四面八方发射线，让它们在方块之间反弹几次，看
 *   - 多少方向是敞开的（射出去碰不到东西 = 声音跑掉了，不会回来）
 *   - 碰到墙之前平均走多远（空间越大越远，回声来得越晚、拖得越长）
 *   - 碰到的方块平均吸音多少（石头几乎全反射，羊毛吸掉一大半，还会吸掉高音）
 * 再用声学里的 Eyring 公式推出混响时间（声音衰减 60 dB 要多久），交给 [EnvironmentAudio]。
 *
 * 在游戏线程上跑（要读方块），几 tick 算一次，每次只发几十条射线。
 */
object RoomAcoustics {
    /** 算出来的空间参数 */
    class Room(
        /** 封闭程度 0..1（1 = 四面都是墙） */
        val enclosure: Float,
        /** 混响时间（秒） */
        val decaySeconds: Float,
        /** 第一次反射回来要多久（毫秒） */
        val earlyDelayMs: Float,
        /** 碰到的方块平均吸音系数 0..1 */
        val absorption: Float,
        /** 回声偏哪边：-1 左，1 右 */
        val pan: Float,
    )

    @Volatile var room: Room? = null
        private set

    /** 调试：把射线记下来让界面画出来 */
    @Volatile var debugRays = false
    /** 上一次测量的射线段，每段 7 个数：起点 xyz、终点 xyz、第几次反弹（-1 = 射出去没碰到东西） */
    @Volatile var rays = FloatArray(0)
        private set

    private const val RAYS = 48
    private const val BOUNCES = 4
    /** 第一段射线最远看多少格；更远就当这个方向是敞开的 */
    private const val FIRST_RANGE = 32.0
    /** 一条射线（含反弹）总共最多走多少格 */
    private const val PATH_RANGE = 64.0
    /** 声速：一格 = 1 米 */
    private const val SPEED_OF_SOUND = 343f
    /** 每次反射除了材质本身，额外损失的比例 */
    private const val BASE_LOSS = 0.06f
    /** 每隔几 tick 算一次 */
    private const val INTERVAL = 4

    private val random = Random()
    private var tick = 0
    private var smoothed: Room? = null

    /** 游戏线程每 tick 调；不在世界里 / 没开环境音效时清掉 */
    fun tick(platform: Platform, active: Boolean) {
        if (!active) {
            room = null
            smoothed = null
            rays = FloatArray(0)
            return
        }
        if (tick++ % INTERVAL != 0) return
        val pose = platform.player ?: run { room = null; smoothed = null; return }
        val measured = try { measure(platform, pose.eyeX, pose.eyeY, pose.eyeZ, pose.yaw) } catch (_: Exception) { null } ?: return
        // 每次射线方向随机转一点，结果会抖：和上一次的平均一下
        val last = smoothed
        val next = if (last == null) measured else Room(
            mix(last.enclosure, measured.enclosure), mix(last.decaySeconds, measured.decaySeconds),
            mix(last.earlyDelayMs, measured.earlyDelayMs), mix(last.absorption, measured.absorption), mix(last.pan, measured.pan))
        smoothed = next
        room = next
    }

    private fun mix(a: Float, b: Float) = a + (b - a) * 0.5f

    private fun measure(platform: Platform, ex: Double, ey: Double, ez: Double, yaw: Float): Room {
        // 玩家右手方向（原版 yaw：0 = 朝南 +Z，顺时针增大）
        val yawRad = Math.toRadians(yaw.toDouble())
        val rightX = -cos(yawRad)
        val rightZ = -sin(yawRad)

        val spin = random.nextDouble() * 2 * PI
        var escaped = 0f
        var firstHits = 0
        var firstDistance = 0.0
        var absorbed = 0.0
        var hits = 0
        var left = 0.0
        var right = 0.0
        val dir = DoubleArray(3)
        val hit = Hit()
        val record = if (debugRays) ArrayList<Float>(RAYS * (BOUNCES + 1) * 7) else null
        fun segment(x: Double, y: Double, z: Double, length: Double, bounce: Int) {
            if (record == null) return
            record.add(x.toFloat()); record.add(y.toFloat()); record.add(z.toFloat())
            record.add((x + dir[0] * length).toFloat()); record.add((y + dir[1] * length).toFloat()); record.add((z + dir[2] * length).toFloat())
            record.add(bounce.toFloat())
        }
        for (i in 0 until RAYS) {
            sphere(i, spin, dir)
            var ox = ex; var oy = ey; var oz = ez
            var travelled = 0.0
            for (bounce in 0..BOUNCES) {
                val range = if (bounce == 0) FIRST_RANGE else PATH_RANGE - travelled
                if (range <= 0 || !trace(platform, ox, oy, oz, dir, range, hit)) {
                    if (range > 0) segment(ox, oy, oz, range, -1)
                    // 第一段就没碰到东西：这个方向敞开；反弹后才跑掉：算半个开口（比如门窗）
                    if (bounce == 0) escaped += 1f else if (range > 0) escaped += 0.5f / BOUNCES
                    break
                }
                if (bounce == 0) {
                    firstHits++
                    firstDistance += hit.distance
                    // 近处的墙对左右感觉影响更大
                    val weight = 1.0 / (1.0 + hit.distance)
                    val side = dir[0] * rightX + dir[2] * rightZ
                    if (side > 0) right += side * weight else left -= side * weight
                }
                segment(ox, oy, oz, hit.distance, bounce)
                absorbed += hit.absorption
                hits++
                travelled += hit.distance
                ox += dir[0] * (hit.distance - 1e-3); oy += dir[1] * (hit.distance - 1e-3); oz += dir[2] * (hit.distance - 1e-3)
                reflect(dir, hit.axis)
            }
        }
        if (record != null) rays = record.toFloatArray()
        val open = (escaped / RAYS).coerceIn(0f, 1f)
        val enclosure = 1f - open
        val meanFree = if (firstHits > 0) (firstDistance / firstHits).toFloat() else FIRST_RANGE.toFloat()
        val absorption = if (hits > 0) (absorbed / hits).toFloat() else 0f
        // 敞开的方向当作吸音 100% 的"墙"；墙面再加一点基础损耗（空气、凹凸不平的表面），不然小石头房会响得像教堂
        val effective = (enclosure * (absorption + BASE_LOSS) + open).coerceIn(0.01f, 0.99f)
        // Eyring 公式：T60 = 0.161 · V / (−S · ln(1 − α))，平均自由程 l = 4V/S
        val decay = (0.161f * meanFree / 4f / -ln(1f - effective)).coerceIn(0.1f, 3.5f)
        val early = (meanFree * 2f / SPEED_OF_SOUND * 1000f).coerceIn(2f, 80f)
        val pan = if (left + right > 1e-6) ((right - left) / (right + left)).toFloat() else 0f
        return Room(enclosure, decay, early, absorption, pan)
    }

    /** 均匀分布在球面上的第 [i] 个方向（黄金角螺旋），整体绕竖轴转 [spin] */
    private fun sphere(i: Int, spin: Double, out: DoubleArray) {
        val y = 1.0 - 2.0 * (i + 0.5) / RAYS
        val r = sqrt(1.0 - y * y)
        val theta = i * 2.399963229728653 + spin
        out[0] = cos(theta) * r; out[1] = y; out[2] = sin(theta) * r
    }

    /** 镜面反射，再掺一点随机方向（真实的墙面不是镜子，回声会散开） */
    private fun reflect(dir: DoubleArray, axis: Int) {
        dir[axis] = -dir[axis]
        val scatter = 0.3
        var x = dir[0] + (random.nextDouble() * 2 - 1) * scatter
        var y = dir[1] + (random.nextDouble() * 2 - 1) * scatter
        var z = dir[2] + (random.nextDouble() * 2 - 1) * scatter
        // 散开后不能穿回墙里
        val n = doubleArrayOf(x, y, z)
        if (n[axis] * dir[axis] < 0) n[axis] = -n[axis]
        x = n[0]; y = n[1]; z = n[2]
        val len = sqrt(x * x + y * y + z * z)
        dir[0] = x / len; dir[1] = y / len; dir[2] = z / len
    }

    private class Hit {
        var distance = 0.0
        var axis = 0
        var absorption = 0f
    }

    /** 沿方块格子一格一格走（3D DDA），碰到挡声音的方块返回 true */
    private fun trace(platform: Platform, ox: Double, oy: Double, oz: Double, dir: DoubleArray, range: Double, hit: Hit): Boolean {
        var x = floor(ox).toInt(); var y = floor(oy).toInt(); var z = floor(oz).toInt()
        val stepX = if (dir[0] > 0) 1 else -1
        val stepY = if (dir[1] > 0) 1 else -1
        val stepZ = if (dir[2] > 0) 1 else -1
        val deltaX = if (dir[0] == 0.0) Double.MAX_VALUE else abs(1.0 / dir[0])
        val deltaY = if (dir[1] == 0.0) Double.MAX_VALUE else abs(1.0 / dir[1])
        val deltaZ = if (dir[2] == 0.0) Double.MAX_VALUE else abs(1.0 / dir[2])
        var maxX = if (dir[0] == 0.0) Double.MAX_VALUE else (if (stepX > 0) x + 1 - ox else ox - x) * deltaX
        var maxY = if (dir[1] == 0.0) Double.MAX_VALUE else (if (stepY > 0) y + 1 - oy else oy - y) * deltaY
        var maxZ = if (dir[2] == 0.0) Double.MAX_VALUE else (if (stepZ > 0) z + 1 - oz else oz - z) * deltaZ
        while (true) {
            val t: Double
            val axis: Int
            if (maxX < maxY && maxX < maxZ) { t = maxX; axis = 0; x += stepX; maxX += deltaX }
            else if (maxY < maxZ) { t = maxY; axis = 1; y += stepY; maxY += deltaY }
            else { t = maxZ; axis = 2; z += stepZ; maxZ += deltaZ }
            if (t > range) return false
            val a = platform.soundAbsorption(x, y, z)
            if (a >= 0f) {
                hit.distance = t; hit.axis = axis; hit.absorption = a
                return true
            }
        }
    }
}
