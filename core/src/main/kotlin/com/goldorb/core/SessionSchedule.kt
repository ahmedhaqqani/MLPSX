package com.goldorb.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Open/close times of one session, anchored to the market's own time zone so that
 * daylight-saving changes in Tokyo/London/New York are followed automatically.
 */
data class SessionConfig(
    val id: SessionId,
    val open: LocalTime,
    val close: LocalTime,
    val zone: ZoneId,
) {
    companion object {
        val DEFAULTS: List<SessionConfig> = listOf(
            SessionConfig(SessionId.ASIAN, LocalTime.of(9, 0), LocalTime.of(18, 0), ZoneId.of("Asia/Tokyo")),
            SessionConfig(SessionId.LONDON, LocalTime.of(8, 0), LocalTime.of(17, 0), ZoneId.of("Europe/London")),
            SessionConfig(SessionId.NEW_YORK, LocalTime.of(9, 30), LocalTime.of(17, 0), ZoneId.of("America/New_York")),
        )

        fun default(id: SessionId): SessionConfig = DEFAULTS.first { it.id == id }
    }
}

/** One concrete occurrence of a session. */
data class SessionWindow(
    val id: SessionId,
    val tradingDate: LocalDate,
    val open: Instant,
    val close: Instant,
) {
    val rangeEnd: Instant get() = open.plus(OPENING_RANGE)

    companion object {
        val OPENING_RANGE: Duration = Duration.ofMinutes(30)
    }
}

object SessionSchedule {

    fun windowOn(config: SessionConfig, date: LocalDate): SessionWindow {
        val open = ZonedDateTime.of(date, config.open, config.zone)
        var close = ZonedDateTime.of(date, config.close, config.zone)
        if (!close.isAfter(open)) close = close.plusDays(1)
        return SessionWindow(config.id, date, open.toInstant(), close.toInstant())
    }

    /**
     * The most recent weekday occurrence of [config] that has already opened at [now].
     * Before today's open this is the previous trading day's session, so a box keeps
     * showing yesterday's result until its own session opens again.
     */
    fun latestWindow(config: SessionConfig, now: Instant): SessionWindow {
        var date = now.atZone(config.zone).toLocalDate()
        repeat(10) {
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                val w = windowOn(config, date)
                if (!w.open.isAfter(now)) return w
            }
            date = date.minusDays(1)
        }
        error("No session found for ${config.id}")
    }

    /** The next occurrence of [config] that opens after [now]. */
    fun nextWindow(config: SessionConfig, now: Instant): SessionWindow {
        var date = now.atZone(config.zone).toLocalDate()
        repeat(10) {
            if (date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY) {
                val w = windowOn(config, date)
                if (w.open.isAfter(now)) return w
            }
            date = date.plusDays(1)
        }
        error("No upcoming session found for ${config.id}")
    }

    /** Shanghai Gold Exchange day-session open, marked on the Asian chart. */
    val SHANGHAI_OPEN: LocalTime = LocalTime.of(9, 0)
    val SHANGHAI_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** When [time] in [zone] (e.g. a market open or close) falls between [from] (inclusive) and [to], if it does. */
    fun openWithin(time: LocalTime, zone: ZoneId, from: Instant, to: Instant): Instant? {
        val first = from.atZone(zone).toLocalDate().minusDays(1)
        return (0L..2L).map { ZonedDateTime.of(first.plusDays(it), time, zone).toInstant() }
            .firstOrNull { !it.isBefore(from) && it.isBefore(to) }
    }

    /** Converts a session's market-zone time to the wall-clock time in [target] on [onDate]. */
    fun toZone(time: LocalTime, from: ZoneId, target: ZoneId, onDate: LocalDate): LocalTime =
        ZonedDateTime.of(onDate, time, from).withZoneSameInstant(target).toLocalTime()
}
