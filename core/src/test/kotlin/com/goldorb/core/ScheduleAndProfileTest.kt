package com.goldorb.core

import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScheduleAndProfileTest {

    private val london = SessionConfig.default(SessionId.LONDON)
    private val asian = SessionConfig.default(SessionId.ASIAN)
    private val ny = SessionConfig.default(SessionId.NEW_YORK)

    @Test
    fun defaultsFollowDaylightSaving() {
        // 9 Oct 2026: BST and EDT in force.
        val summer = SessionSchedule.latestWindow(london, Instant.parse("2026-10-09T12:00:00Z"))
        assertEquals(Instant.parse("2026-10-09T07:00:00Z"), summer.open)
        // 9 Nov 2026: GMT and EST.
        val winter = SessionSchedule.latestWindow(london, Instant.parse("2026-11-09T12:00:00Z"))
        assertEquals(Instant.parse("2026-11-09T08:00:00Z"), winter.open)
        val nyWinter = SessionSchedule.latestWindow(ny, Instant.parse("2026-11-09T15:00:00Z"))
        assertEquals(Instant.parse("2026-11-09T14:30:00Z"), nyWinter.open)
        assertEquals(Instant.parse("2026-11-09T22:00:00Z"), nyWinter.close)
    }

    @Test
    fun boxKeepsPreviousDayUntilItsSessionOpens() {
        // Tuesday 01:00 UTC: Asian session of Tuesday has opened, London still shows Monday.
        val now = Instant.parse("2026-10-13T01:00:00Z")
        assertEquals("2026-10-13", SessionSchedule.latestWindow(asian, now).tradingDate.toString())
        assertEquals("2026-10-12", SessionSchedule.latestWindow(london, now).tradingDate.toString())
        assertEquals("2026-10-12", SessionSchedule.latestWindow(ny, now).tradingDate.toString())
    }

    @Test
    fun weekendsAreSkipped() {
        val sunday = Instant.parse("2026-10-11T12:00:00Z")
        assertEquals("2026-10-09", SessionSchedule.latestWindow(london, sunday).tradingDate.toString())
        assertEquals("2026-10-12", SessionSchedule.nextWindow(asian, sunday).tradingDate.toString())
    }

    @Test
    fun convertsToUserTimeZone() {
        val karachi = ZoneId.of("Asia/Karachi")
        val d = java.time.LocalDate.of(2026, 10, 9)
        assertEquals(LocalTime.of(5, 0), SessionSchedule.toZone(asian.open, asian.zone, karachi, d))
        assertEquals(LocalTime.of(12, 0), SessionSchedule.toZone(london.open, london.zone, karachi, d))
        assertEquals(LocalTime.of(18, 30), SessionSchedule.toZone(ny.open, ny.zone, karachi, d))
    }

    @Test
    fun valueAreaHoldsSeventyPercent() {
        val bars = listOf(
            Bar(0, 10.0, 11.0, 10.0, 11.0, 10.0),
            Bar(60, 11.0, 12.0, 11.0, 12.0, 60.0),
            Bar(120, 12.0, 13.0, 12.0, 13.0, 20.0),
            Bar(180, 13.0, 14.0, 13.0, 14.0, 10.0),
        )
        val vp = VolumeProfileCalc.compute(bars, rowCount = 4)!!
        assertEquals(1, vp.pocIndex)
        assertEquals(11.5, vp.poc)
        // POC row (60) + row above (20) = 80% ≥ 70%.
        assertEquals(11.0, vp.valueAreaLow)
        assertEquals(13.0, vp.vah)
    }

    @Test
    fun zeroVolumeFallsBackToTimeAtPrice() {
        val bars = List(5) { Bar(it * 60L, 10.0, 10.5, 10.0, 10.5, 0.0) } + Bar(300, 10.5, 12.0, 10.5, 12.0, 0.0)
        val vp = VolumeProfileCalc.compute(bars, rowCount = 8)!!
        assertTrue(vp.poc < 10.5)
    }

    @Test
    fun updaterFreezesClosedSessionsAndAlertsOnlyRecentEvents() = runTest {
        val now = Instant.parse("2026-10-13T01:00:00Z")
        val asianOpen = Instant.parse("2026-10-13T00:00:00Z").epochSecond
        // Asian range 100..110, then a breakout at 00:30.
        val bars = (0 until 30).map { Bar(asianOpen + it * 60, 105.0, 110.0, 100.0, 105.0, 1.0) } +
            (30 until 60).map { Bar(asianOpen + it * 60, 111.0, 112.0, 111.0, 112.0, 1.0) }
        val requestedFrom = mutableListOf<Instant>()
        val updater = OrbUpdater { from -> requestedFrom += from; bars }

        val configs = SessionConfig.DEFAULTS
        val first = updater.update(configs, emptyMap(), now)
        assertEquals(BreakStatus.BREAKOUT_UP, first.snapshots[SessionId.ASIAN]!!.status)
        // Breakout candle at 00:30 closed 00:35 — older than 15 min, so no alert on first launch.
        assertTrue(first.alerts.none { it.event.type == EventType.BREAKOUT_UP })
        // London/NY of Monday closed long ago and are final.
        assertEquals(Phase.CLOSED, first.snapshots[SessionId.LONDON]!!.phase)

        requestedFrom.clear()
        updater.update(configs, first.snapshots, now.plusSeconds(300))
        // Only the Asian session is refetched.
        assertEquals(listOf(Instant.ofEpochSecond(asianOpen)), requestedFrom)
    }

    @Test
    fun nextRefreshIsJustAfterCandleClose() {
        assertEquals(Instant.parse("2026-10-09T07:35:20Z"), OrbUpdater.nextRefresh(Instant.parse("2026-10-09T07:31:00Z")))
        assertEquals(Instant.parse("2026-10-09T07:40:20Z"), OrbUpdater.nextRefresh(Instant.parse("2026-10-09T07:35:20Z")))
    }

    @Test
    fun sleepsUntilNextRangeWhenAllSessionsClosed() = runTest {
        // Friday 22:30 UTC: NY has closed; everything is final until Monday's Asian range.
        val now = Instant.parse("2026-10-09T22:30:00Z")
        val result = OrbUpdater { emptyList() }.update(SessionConfig.DEFAULTS, emptyMap(), now)
        assertTrue(result.snapshots.values.all { it.isFinal })
        assertEquals(
            Instant.parse("2026-10-12T00:30:20Z"),
            OrbUpdater.nextWake(SessionConfig.DEFAULTS, result.snapshots, now, lastRefreshFailed = false),
        )
        assertEquals(
            Instant.parse("2026-10-09T22:30:20Z"),
            OrbUpdater.nextWake(SessionConfig.DEFAULTS, result.snapshots, now, lastRefreshFailed = true),
        )
    }
}
