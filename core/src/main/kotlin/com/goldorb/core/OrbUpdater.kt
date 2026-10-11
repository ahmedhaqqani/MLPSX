package com.goldorb.core

import java.time.Instant

data class Alert(val session: SessionId, val event: SignalEvent, val snapshot: SessionSnapshot)

data class UpdateResult(val snapshots: Map<SessionId, SessionSnapshot>, val alerts: List<Alert>)

/**
 * Refreshes the three session boxes.
 *
 * Each box shows the latest occurrence of its session that has opened. A finished
 * (closed) occurrence is frozen and never refetched; when the next occurrence opens
 * the box resets. Only sessions that still change are fetched, in a single request.
 */
class OrbUpdater(private val fetchOneMinuteBars: suspend (from: Instant) -> List<Bar>) {

    suspend fun update(
        configs: List<SessionConfig>,
        previous: Map<SessionId, SessionSnapshot>,
        now: Instant,
        profile: ProfileSettings = ProfileSettings(),
        alertMaxAgeSeconds: Long = 15 * 60,
    ): UpdateResult {
        val windows = configs.map { SessionSchedule.latestWindow(it, now) }
        val stale = windows.filter { w ->
            val prev = previous[w.id]
            prev == null || prev.openEpoch != w.open.epochSecond || !prev.isFinal
        }
        if (stale.isEmpty()) return UpdateResult(previous, emptyList())

        val bars = fetchOneMinuteBars(stale.minOf { it.open })
        val snapshots = previous.toMutableMap()
        val alerts = mutableListOf<Alert>()
        for (w in stale) {
            val snap = OrbEngine.evaluate(w, bars, now, profile)
            OrbEngine.newEvents(previous[w.id], snap)
                .filter { now.epochSecond - (it.time + OrbEngine.CANDLE_SECONDS) <= alertMaxAgeSeconds }
                .forEach { alerts += Alert(w.id, it, snap) }
            snapshots[w.id] = snap
        }
        return UpdateResult(snapshots, alerts)
    }

    companion object {
        /**
         * When to wake up next: after the next 5-minute close while any session is still
         * live (or the last refresh failed); otherwise just after the next opening range
         * completes, so nothing runs overnight or at weekends.
         */
        fun nextWake(
            configs: List<SessionConfig>,
            snapshots: Map<SessionId, SessionSnapshot>,
            now: Instant,
            lastRefreshFailed: Boolean,
        ): Instant {
            val allFinal = configs.all { cfg ->
                val snap = snapshots[cfg.id]
                snap != null && snap.isFinal && snap.openEpoch == SessionSchedule.latestWindow(cfg, now).open.epochSecond
            }
            if (lastRefreshFailed || !allFinal) return nextRefresh(now)
            return configs.minOf { SessionSchedule.nextWindow(it, now).rangeEnd }.plusSeconds(20)
        }

        /** Next time worth refreshing: just after the next 5-minute candle closes. */
        fun nextRefresh(now: Instant, delaySeconds: Long = 20): Instant {
            val s = now.epochSecond
            val nextBoundary = (s - delaySeconds) / OrbEngine.CANDLE_SECONDS * OrbEngine.CANDLE_SECONDS +
                OrbEngine.CANDLE_SECONDS + delaySeconds
            return Instant.ofEpochSecond(nextBoundary)
        }
    }
}
