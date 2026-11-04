package me.sorihiko.dango.modern

import me.sorihiko.dango.DangoClient
import me.sorihiko.dango.DangoLogger
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.events.TickEvent
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.events.WorldRenderEndEvent

/** Fabric / Forge 入口调用：启动和事件转发（两个加载器共用） */
object ModernDango {
    /** 是否已经启动（启动前不处理任何事件） */
    @JvmStatic
    var started = false
        private set

    @JvmStatic
    fun start() {
        if (started) return
        started = true
        JitWorkaround.install()
        DangoClient.start(ModernPlatform, ModernGfx)
    }

    /** 每个游戏 tick 开头 */
    @JvmStatic
    fun tick() {
        if (!started) return
        if (!McCompat.glAvailable) {
            McCompat.notifyNoGl()   // 只提示一次
            DangoMusic.callEvent(TickEvent)
            return
        }
        try {
            ModernGl.init()
        } catch (t: Throwable) {
            DangoLogger.error("Dango Music GL init failed: {}", t)
        }
        DangoMusic.callEvent(TickEvent)
    }

    /**
     * 世界画完（手和 HUD 之前）。
     *
     * 镜头转向自己按镜头算，不用加载器事件给的矩阵：Fabric 和 Forge 给的矩阵不一样
     * （Forge 那边会让歌词左右反过来），自己算两边结果一致，和原版画实体用的完全相同。
     * 画的期间把原版的视图矩阵设成单位矩阵，镜头转向只算这一次。
     */
    /**
     * 原版画世界前把投影矩阵（含视角摇晃）交给显卡的那一刻，mixin 把它抄一份过来。
     * 有了这份就不用每帧再从显存读回来（读显存要等显卡把手上的活干完，会掉帧）
     */
    @JvmStatic
    fun worldProjection(projection: org.joml.Matrix4fc) {
        McCompat.captureWorldProjection(projection)
    }

    @JvmStatic
    fun worldRenderEnd() {
        if (!started || !McCompat.glAvailable) return
        // 每帧都会画世界（各版本都走这里），在这里数帧、按时报性能
        Perf.frame()
        ModernWorld.basePose.identity()
            .rotateX(Math.toRadians(McCompat.cameraXRot().toDouble()).toFloat())
            .rotateY(Math.toRadians(McCompat.cameraYRot().toDouble() + 180.0).toFloat())
        Perf.begin("world")
        McCompat.beginWorld()
        McCompat.pushIdentityModelView()
        try {
            // partialTicks 目前没有地方用到，各版本拿它的写法又不一样，就不传了
            DangoMusic.callEvent(WorldRenderEndEvent(0f))
            // 1.21.11 / 26.x 的界面是先收集、最后统一画：HUD 先在这里画到自己的贴图上，原版画完暗角时再交出去
            McCompat.renderHudLayer { DangoMusic.callEvent(HudRenderEvent) }
        } finally {
            Imm.flush()
            BorrowedGlyphTextures.endPass()
            Gl.forget()
            McCompat.popModelView()
            McCompat.endWorld()
            Perf.end("world")
        }
    }

    /**
     * 原版画完暗角（HUD 里，快捷栏之前）：Song card、灵动岛、Now playing 在这里画 / 交给原版，不会被暗角压暗。
     * 26.x 选 Vulkan 时，整个世界那一层（含歌词）也在这里交出去。
     */
    @JvmStatic
    fun hudOverlay(graphics: Any) {
        if (!started || !McCompat.glAvailable) return
        Perf.begin("hud")
        try {
            McCompat.hudOverlay(graphics) { DangoMusic.callEvent(HudRenderEvent) }
        } finally {
            Imm.flush()
            BorrowedGlyphTextures.endPass()
            Gl.forget()
            Perf.end("hud")
        }
    }

    /** 原版开始画界面之前（26.x：我们的界面在这时画到它自己的贴图上） */
    @JvmStatic
    fun beforeGui() {
        if (!started || !McCompat.glAvailable) return
        Perf.begin("screen")
        McCompat.beforeGui()
        Imm.flush()
        BorrowedGlyphTextures.endPass()
        Gl.forget()
        Perf.end("screen")
    }

    @JvmStatic
    fun mainMenuRender(graphics: Any) {
        if (!started || !McCompat.glAvailable || !ModernPlatform.inMainMenu) return
        if (!me.sorihiko.dango.music.MusicPlayer.active) return
        ModernGl.init()
        McCompat.mainMenuNowPlaying(graphics)
        Imm.flush()
        BorrowedGlyphTextures.endPass()
        Gl.forget()
    }
}
