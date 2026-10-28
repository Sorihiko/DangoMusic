package me.sorihiko.dango.playback.salt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Salt 播放时钟：协议 4.4 客户端显示规则（对齐 saltbridge DisplayedPositionTest 语义） */
class SaltClockTest {

    private val clock = SaltClock(staleAfterMs = 3000L)

    /** 默认锚点：reported=10000、锚点年龄 2000ms */
    private fun status(
        instance: String = "inst-1",
        generation: Long = 3,
        reported: Long? = 10_000L,
        reportedAt: Long? = 8_000L,
        servedAt: Long = 10_000L,
        canExtrapolate: Boolean = true,
        maxMs: Long = 3_000L,
        durationMs: Long = 225_000L,
        receivedMono: Long = 100_000L,
        nowMono: Long = 100_000L,
    ) = clock.onStatus(instance, generation, reported, reportedAt, servedAt, canExtrapolate, maxMs, durationMs, receivedMono, nowMono)

    @Test
    fun freshAnchorProjectsForward() {
        // reported=10000 + min(2000, 3000) = 12000
        assertEquals(12_000L, status())
    }

    @Test
    fun localElapsedAdvancesBetweenPolls() {
        status(receivedMono = 100_000L, nowMono = 100_000L)
        // 本地又过 500ms：elapsed=2500 → 12500
        assertEquals(12_500L, clock.displayed(100_500L))
    }

    @Test
    fun extrapolationCappedByMaxMs() {
        status(receivedMono = 100_000L)
        // elapsed = 2000 + 10000 → cap 3000 → 13000
        assertEquals(13_000L, clock.displayed(110_000L))
    }

    @Test
    fun disabledStopsInterpolationButDoesNotRewind() {
        status(receivedMono = 100_000L, nowMono = 100_000L)
        assertEquals(13_000L, clock.displayed(110_000L))     // 推到上限
        // 同一锚点过期响应：disabled，target=reported=10000，但不得倒退
        val v = status(canExtrapolate = false, receivedMono = 111_000L, nowMono = 111_000L)
        assertEquals(13_000L, v)
    }

    @Test
    fun repeatedStaleResponsesDoNotJitter() {
        status(receivedMono = 100_000L)
        clock.displayed(110_000L)
        assertEquals(13_000L, status(canExtrapolate = false, receivedMono = 112_000L, nowMono = 112_000L))
        assertEquals(13_000L, status(canExtrapolate = false, receivedMono = 113_000L, nowMono = 113_000L))
    }

    @Test
    fun newAnchorForwardMovesOn() {
        status(receivedMono = 100_000L)
        clock.displayed(110_000L)                             // 13000
        // 新锚点 14000（reportedAtMs 更新）→ 允许前进
        val v = status(reported = 14_000L, reportedAt = 11_000L, servedAt = 11_000L, receivedMono = 111_000L, nowMono = 111_000L)
        assertEquals(14_000L, v)
    }

    @Test
    fun seekBackwardAllowsDecrease() {
        status(receivedMono = 100_000L)
        clock.displayed(110_000L)                             // 13000
        // seek 到 5000：新锚点且回退超过 staleAfterMs → 允许下调
        val v = status(reported = 5_000L, reportedAt = 11_000L, servedAt = 11_000L, receivedMono = 111_000L, nowMono = 111_000L)
        assertEquals(5_000L, v)
    }

    @Test
    fun tinyBackwardNewAnchorIgnored() {
        status(receivedMono = 100_000L)
        clock.displayed(110_000L)                             // 13000
        // 新锚点只回退 500（< staleAfterMs）：保持 13000
        val v = status(reported = 12_500L, reportedAt = 11_000L, servedAt = 11_000L, receivedMono = 111_000L, nowMono = 111_000L)
        assertEquals(13_000L, v)
    }

    @Test
    fun trackChangeResets() {
        status(receivedMono = 100_000L)
        clock.displayed(110_000L)
        // 切歌：generation 变化 → 以新锚点为准
        val v = status(generation = 4, reported = 0L, reportedAt = 11_000L, servedAt = 11_000L, receivedMono = 111_000L, nowMono = 111_000L)
        assertEquals(0L, v)
    }

    @Test
    fun noAnchorYieldsNull() {
        assertNull(status(reported = null, reportedAt = null))
        assertNull(clock.displayed(100_000L))
    }

    @Test
    fun durationCapsProjection() {
        status(reported = 224_000L, reportedAt = 8_000L, servedAt = 10_000L, durationMs = 225_000L, receivedMono = 100_000L)
        // 224000 + min(2000+10000, 3000) = 227000 → 曲长封顶 225000
        assertEquals(225_000L, clock.displayed(110_000L))
    }
}
