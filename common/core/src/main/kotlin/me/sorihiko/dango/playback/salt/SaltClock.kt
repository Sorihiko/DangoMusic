package me.sorihiko.dango.playback.salt

/**
 * Salt 播放时钟。
 *
 * - 服务端只给锚点（reportedMs + 时间戳），不外推；本地用单调时钟推进显示值；
 * - 外推公式：reportedMs + min(锚点年龄 + 本地已耗时, animation.maxMs)，再受已知曲长限制；
 * - `animation.mode=disabled` 只停止插值，**不**退回 reportedMs；
 * - 同一锚点 / 过期旧锚点的响应不倒退（冻结已显示位置）；
 * - 新锚点（reportedAtMs 更大）明显回退超过 staleAfterMs 时视为 seek / 重播，允许下调。
 *
 */
class SaltClock(private val staleAfterMs: Long = 3000L) {

    private var instanceId: String? = null
    private var generation: Long? = null

    private var reportedMs: Long? = null
    /** 锚点年龄（servedAtMs - reportedAtMs）：响应产生时已过去的量 */
    private var anchorAgeMs = 0L
    private var receivedMonoMs = 0L
    private var lastReportedAtMs = Long.MIN_VALUE
    private var canExtrapolate = false
    private var maxMs = 0L
    private var durationMs = 0L

    private var displayedMs: Long? = null

    /** 切实例 / 切代次时重置 */
    fun reset() {
        instanceId = null
        generation = null
        reportedMs = null
        lastReportedAtMs = Long.MIN_VALUE
        displayedMs = null
    }

    /**
     * 收到 /status 时调用。返回更新后的显示位置。
     */
    fun onStatus(
        instanceId: String,
        generation: Long,
        reportedMs: Long?,
        reportedAtMs: Long?,
        servedAtMs: Long,
        canExtrapolate: Boolean,
        maxMs: Long,
        durationMs: Long,
        localReceivedMonoMs: Long,
        localNowMonoMs: Long,
    ): Long? {
        val sameContext = this.instanceId == instanceId && this.generation == generation

        val age = if (reportedMs != null && reportedAtMs != null) {
            (servedAtMs - reportedAtMs).coerceAtLeast(0L)
        } else 0L

        // 新锚点 = 服务端事件时间更新的样本；过期乱序响应不算
        val isNewAnchor = reportedAtMs != null && reportedAtMs > lastReportedAtMs

        this.instanceId = instanceId
        this.generation = generation
        this.reportedMs = reportedMs
        this.anchorAgeMs = age
        this.receivedMonoMs = localReceivedMonoMs
        this.canExtrapolate = canExtrapolate
        this.maxMs = maxMs
        this.durationMs = durationMs
        if (isNewAnchor) this.lastReportedAtMs = reportedAtMs

        val target = when {
            reportedMs == null -> null
            canExtrapolate -> project(localNowMonoMs)
            else -> reportedMs
        }

        if (!sameContext) {
            if (reportedAtMs != null) this.lastReportedAtMs = reportedAtMs
            displayedMs = target
            return displayedMs
        }

        val previous = displayedMs
        val r = this.reportedMs
        displayedMs = when {
            target == null -> previous
            previous == null -> target
            target >= previous -> target
            // 仅新锚点的明显回退（seek / 同曲重播）允许下调；过期响应冻结不倒退
            isNewAnchor && r != null && r < previous - staleAfterMs -> r
            else -> previous
        }
        return displayedMs
    }

    /** 两次轮询之间读取显示位置；可外推时按本地单调时钟推进（只前进不倒退） */
    fun displayed(localNowMonoMs: Long): Long? {
        val previous = displayedMs ?: return null
        if (!canExtrapolate) return previous
        val projected = project(localNowMonoMs) ?: return previous
        if (projected > previous) displayedMs = projected
        return displayedMs
    }

    /** reportedMs + min(锚点年龄 + 本地耗时, maxMs)，再受曲长限制 */
    private fun project(localNowMonoMs: Long): Long? {
        val r = reportedMs ?: return null
        val elapsed = anchorAgeMs + (localNowMonoMs - receivedMonoMs).coerceAtLeast(0L)
        var v = r + elapsed.coerceIn(0L, maxMs.coerceAtLeast(0L))
        if (durationMs > 0) v = v.coerceAtMost(durationMs)
        return v.coerceAtLeast(0L)
    }
}
