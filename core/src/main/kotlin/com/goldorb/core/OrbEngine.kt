package com.goldorb.core

import java.time.Instant

data class ProfileSettings(val rowCount: Int = 24, val valueAreaPct: Double = 0.70)

/**
 * Turns raw 1-minute bars into the state of one session box.
 *
 * Rules:
 *  - Opening range (OR) = high/low of the first 30 minutes after the session opens.
 *  - Volume profile (POC / VAH / VAL) is built from the same 30 minutes.
 *  - Each completed 5-minute close after the range is classified:
 *      above OR high → green, below OR low → red,
 *      inside the range: above VAH → light green, below VAL → light red, otherwise orange.
 *  - A breakout that closes back inside the range becomes FAILED (purple);
 *    breaking out again afterwards is a RE-BREAK (green / red again).
 *
 * The engine is stateless: every refresh recomputes the session from its bars.
 */
object OrbEngine {

    const val CANDLE_SECONDS = 300L

    /** How long after a bucket ends we treat it as complete even if no later bar arrived. */
    const val GRACE_SECONDS = 180L

    fun evaluate(
        window: SessionWindow,
        bars: List<Bar>,
        now: Instant,
        profile: ProfileSettings = ProfileSettings(),
    ): SessionSnapshot {
        val nowS = now.epochSecond
        val open = window.open.epochSecond
        val rangeEnd = window.rangeEnd.epochSecond
        val close = window.close.epochSecond

        val sorted = bars.distinctBy { it.t }.sortedBy { it.t }
        val latestBar = sorted.lastOrNull()?.t
        fun complete(end: Long) = nowS >= end && ((latestBar != null && latestBar >= end) || nowS >= end + GRACE_SECONDS)

        val sessionBars = sorted.filter { it.t in open until close }
        val candles = aggregate(sessionBars, open, CANDLE_SECONDS)
        val base = SessionSnapshot(
            session = window.id,
            tradingDate = window.tradingDate.toString(),
            openEpoch = open,
            rangeEndEpoch = rangeEnd,
            closeEpoch = close,
            phase = Phase.FORMING,
            candles = candles,
            updatedAt = nowS,
        )

        val rangeBars = sessionBars.filter { it.t < rangeEnd }
        if (!complete(rangeEnd)) {
            return base.copy(
                orHigh = rangeBars.maxOfOrNull { it.h },
                orLow = rangeBars.minOfOrNull { it.l },
            )
        }
        if (rangeBars.isEmpty()) {
            return base.copy(phase = if (complete(close)) Phase.CLOSED else Phase.NO_DATA)
        }

        val orHigh = rangeBars.maxOf { it.h }
        val orLow = rangeBars.minOf { it.l }
        val vp = VolumeProfileCalc.compute(rangeBars, profile.rowCount, profile.valueAreaPct)!!
        val vah = vp.vah.coerceAtMost(orHigh)
        val vaLow = vp.valueAreaLow.coerceAtLeast(orLow)

        val events = mutableListOf(SignalEvent(rangeEnd, EventType.RANGE_SET, sessionBars.last { it.t < rangeEnd }.c))
        var status = BreakStatus.NONE
        var hadBreak = false
        var above = 0
        var inside = 0
        var below = 0
        var zone: Zone? = null
        var last: Bar? = null

        val tracked = candles.filter { it.t >= rangeEnd && it.t + CANDLE_SECONDS <= close && complete(it.t + CANDLE_SECONDS) }
        for (candle in tracked) {
            val z = zoneOf(candle.c, orHigh, orLow, vah, vaLow)
            zone = z
            last = candle
            val wasUp = status == BreakStatus.BREAKOUT_UP || status == BreakStatus.REBREAK_UP
            val wasDown = status == BreakStatus.BREAKOUT_DOWN || status == BreakStatus.REBREAK_DOWN
            when (z) {
                Zone.ABOVE -> {
                    above++
                    if (!wasUp) {
                        if (wasDown) events += SignalEvent(candle.t, EventType.FAILED_DOWN, candle.c)
                        status = if (hadBreak) BreakStatus.REBREAK_UP else BreakStatus.BREAKOUT_UP
                        events += SignalEvent(candle.t, if (hadBreak) EventType.REBREAK_UP else EventType.BREAKOUT_UP, candle.c)
                        hadBreak = true
                    }
                }
                Zone.BELOW -> {
                    below++
                    if (!wasDown) {
                        if (wasUp) events += SignalEvent(candle.t, EventType.FAILED_UP, candle.c)
                        status = if (hadBreak) BreakStatus.REBREAK_DOWN else BreakStatus.BREAKOUT_DOWN
                        events += SignalEvent(candle.t, if (hadBreak) EventType.REBREAK_DOWN else EventType.BREAKOUT_DOWN, candle.c)
                        hadBreak = true
                    }
                }
                else -> {
                    inside++
                    if (wasUp) {
                        status = BreakStatus.FAILED_UP
                        events += SignalEvent(candle.t, EventType.FAILED_UP, candle.c)
                    } else if (wasDown) {
                        status = BreakStatus.FAILED_DOWN
                        events += SignalEvent(candle.t, EventType.FAILED_DOWN, candle.c)
                    }
                }
            }
        }

        return base.copy(
            phase = if (complete(close)) Phase.CLOSED else Phase.ACTIVE,
            orHigh = orHigh,
            orLow = orLow,
            vah = vah,
            valueAreaLow = vaLow,
            poc = vp.poc,
            lastClose = last?.c,
            lastCandleTime = last?.t,
            zone = zone,
            status = status,
            dot = dotFor(status, zone),
            above = above,
            inside = inside,
            below = below,
            events = events,
        )
    }

    fun zoneOf(close: Double, orHigh: Double, orLow: Double, vah: Double, vaLow: Double): Zone = when {
        close > orHigh -> Zone.ABOVE
        close < orLow -> Zone.BELOW
        close > vah -> Zone.LEAN_UP
        close < vaLow -> Zone.LEAN_DOWN
        else -> Zone.INSIDE
    }

    fun dotFor(status: BreakStatus, zone: Zone?): Dot = when (status) {
        BreakStatus.BREAKOUT_UP, BreakStatus.REBREAK_UP -> Dot.GREEN
        BreakStatus.BREAKOUT_DOWN, BreakStatus.REBREAK_DOWN -> Dot.RED
        BreakStatus.FAILED_UP, BreakStatus.FAILED_DOWN -> Dot.PURPLE
        BreakStatus.NONE -> when (zone) {
            Zone.LEAN_UP -> Dot.LIGHT_GREEN
            Zone.LEAN_DOWN -> Dot.LIGHT_RED
            else -> Dot.ORANGE
        }
    }

    /** Groups bars into [seconds]-long candles aligned to [anchor] (the session open). */
    fun aggregate(bars: List<Bar>, anchor: Long, seconds: Long): List<Bar> =
        bars.groupBy { anchor + Math.floorDiv(it.t - anchor, seconds) * seconds }
            .toSortedMap()
            .map { (start, group) ->
                Bar(
                    t = start,
                    o = group.first().o,
                    h = group.maxOf { it.h },
                    l = group.minOf { it.l },
                    c = group.last().c,
                    v = group.sumOf { it.v },
                )
            }

    /** Events in [new] that were not already in [old] (same session occurrence). */
    fun newEvents(old: SessionSnapshot?, new: SessionSnapshot): List<SignalEvent> {
        if (old == null || old.openEpoch != new.openEpoch) return new.events
        return new.events.filter { it !in old.events }
    }
}
