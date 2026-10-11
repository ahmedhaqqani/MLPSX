package com.goldorb.core

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrbEngineTest {

    // London, Friday 9 Oct 2026: opens 07:00 UTC (08:00 BST), range ends 07:30, closes 16:00 UTC.
    private val window = SessionSchedule.windowOn(SessionConfig.default(SessionId.LONDON), LocalDate.of(2026, 10, 9))
    private val open = window.open.epochSecond

    /** Opening range 100..110 with most volume around 104-106, then one flat 1m bar per [closes] entry, 5 min apart. */
    private fun bars(vararg closes: Double): List<Bar> {
        val out = mutableListOf<Bar>()
        for (m in 0 until 30) {
            val (lo, hi) = when (m) { 3 -> 100.0 to 103.0; 20 -> 107.0 to 110.0; else -> 104.0 to 106.0 }
            out += Bar(open + m * 60, lo, hi, lo, hi, if (m == 3 || m == 20) 1.0 else 10.0)
        }
        closes.forEachIndexed { i, c ->
            val start = open + 30 * 60 + i * 300
            for (m in 0 until 5) out += Bar(start + m * 60, c, c, c, c, 5.0)
        }
        return out
    }

    private fun at(minutesAfterOpen: Long) = Instant.ofEpochSecond(open + minutesAfterOpen * 60)

    @Test
    fun formingRangeHasNoSignal() {
        val s = OrbEngine.evaluate(window, bars().filter { it.t < open + 600 }, at(10))
        assertEquals(Phase.FORMING, s.phase)
        assertEquals(Dot.GREY, s.dot)
        assertEquals(106.0, s.orHigh)
        assertEquals(100.0, s.orLow)
    }

    @Test
    fun rangeAndValueArea() {
        val s = OrbEngine.evaluate(window, bars(105.0), at(39))
        assertEquals(Phase.ACTIVE, s.phase)
        assertEquals(110.0, s.orHigh)
        assertEquals(100.0, s.orLow)
        val vah = s.vah!!
        val vaLow = s.valueAreaLow!!
        assertTrue(vah in 105.5..106.3, "vah=$vah")
        assertTrue(vaLow in 104.0..104.5, "val=$vaLow")
        assertTrue(s.poc!! in vaLow..vah)
        assertEquals(Zone.INSIDE, s.zone)
        assertEquals(Dot.ORANGE, s.dot)
        assertEquals(1, s.inside)
    }

    @Test
    fun leanZonesInsideRange() {
        assertEquals(Dot.LIGHT_GREEN, OrbEngine.evaluate(window, bars(108.0), at(39)).dot)
        assertEquals(Dot.LIGHT_RED, OrbEngine.evaluate(window, bars(101.0), at(39)).dot)
    }

    @Test
    fun breakoutFailAndRebreak() {
        val all = bars(105.0, 111.0, 112.0, 108.0, 105.0, 113.0, 99.0)
        fun after(n: Int) = OrbEngine.evaluate(window, all, at(30 + n * 5L + 4))

        after(2).let {
            assertEquals(BreakStatus.BREAKOUT_UP, it.status)
            assertEquals(Dot.GREEN, it.dot)
            assertEquals(1, it.above)
        }
        after(3).let { assertEquals(2, it.above); assertEquals(BreakStatus.BREAKOUT_UP, it.status) }
        after(4).let {
            assertEquals(BreakStatus.FAILED_UP, it.status)
            assertEquals(Dot.PURPLE, it.dot)
        }
        after(5).let { assertEquals(Dot.PURPLE, it.dot); assertEquals(3, it.inside) }
        after(6).let {
            assertEquals(BreakStatus.REBREAK_UP, it.status)
            assertEquals(Dot.GREEN, it.dot)
            assertEquals(3, it.above)
        }
        after(7).let {
            assertEquals(BreakStatus.REBREAK_DOWN, it.status)
            assertEquals(Dot.RED, it.dot)
            assertEquals(1, it.below)
            assertEquals(
                listOf(
                    EventType.RANGE_SET, EventType.BREAKOUT_UP, EventType.FAILED_UP,
                    EventType.REBREAK_UP, EventType.FAILED_UP, EventType.REBREAK_DOWN,
                ),
                it.events.map { e -> e.type },
            )
        }
    }

    @Test
    fun breakdownFirstIsRed() {
        val s = OrbEngine.evaluate(window, bars(99.0), at(39))
        assertEquals(BreakStatus.BREAKOUT_DOWN, s.status)
        assertEquals(Dot.RED, s.dot)
        assertEquals(1, s.below)
    }

    @Test
    fun incompleteCandleIsIgnoredUntilGraceOrNextBar() {
        // Only 2 of the 5 minutes of the first post-range candle exist, and it is 07:32.
        val partial = bars(111.0).filter { it.t < open + 32 * 60 }
        val s = OrbEngine.evaluate(window, partial, at(32))
        assertNull(s.lastClose)
        assertEquals(Dot.ORANGE, s.dot)
    }

    @Test
    fun closedSessionIsFinal() {
        val s = OrbEngine.evaluate(window, bars(111.0), window.close.plusSeconds(600))
        assertEquals(Phase.CLOSED, s.phase)
        assertTrue(s.isFinal)
    }

    @Test
    fun noDataForSession() {
        val s = OrbEngine.evaluate(window, emptyList(), at(60))
        assertEquals(Phase.NO_DATA, s.phase)
        assertNull(s.orHigh)
        // A late feed must not freeze the session.
        assertTrue(!s.isFinal)
    }

    @Test
    fun aggregateAlignsToAnchor() {
        val one = (0 until 10).map { Bar(open + it * 60L, 1.0 + it, 2.0 + it, 0.5 + it, 1.5 + it, 1.0) }
        val five = OrbEngine.aggregate(one, open, 300)
        assertEquals(2, five.size)
        assertEquals(Bar(open, 1.0, 6.0, 0.5, 5.5, 5.0), five[0])
    }

    @Test
    fun sessionVwapIsVolumeWeightedTypicalPrice() {
        val bars = listOf(
            Bar(open, 100.0, 100.0, 100.0, 100.0, 1.0),
            Bar(open + 60, 110.0, 110.0, 110.0, 110.0, 3.0),
            // Second 5m candle: typical price (121 + 115 + 118) / 3 = 118, volume 4.
            Bar(open + 300, 118.0, 121.0, 115.0, 118.0, 4.0),
        )
        val s = OrbEngine.evaluate(window, bars, at(6))
        assertEquals(listOf(107.5, 112.75), s.vwapLine)
        assertEquals(112.75, s.vwap)
    }

    @Test
    fun vwapWithoutVolumeWeightsBarsEqually() {
        val bars = listOf(Bar(open, 100.0, 100.0, 100.0, 100.0, 0.0), Bar(open + 60, 110.0, 110.0, 110.0, 110.0, 0.0))
        assertEquals(105.0, OrbEngine.evaluate(window, bars, at(2)).vwap)
    }
}
