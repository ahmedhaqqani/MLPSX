package com.goldorb.core

import kotlinx.serialization.Serializable

/** One OHLCV candle. [t] is the bar's open time in epoch seconds (UTC). */
@Serializable
data class Bar(
    val t: Long,
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double = 0.0,
)

enum class SessionId(val label: String) {
    ASIAN("Asian"),
    LONDON("London"),
    NEW_YORK("New York"),
}

/** Where a 5-minute close sits relative to the opening range and its value area. */
enum class Zone {
    /** Above OR high (and therefore above VAH). */
    ABOVE,

    /** Inside the range, above VAH. */
    LEAN_UP,

    /** Inside the value area (VAL..VAH). */
    INSIDE,

    /** Inside the range, below VAL. */
    LEAN_DOWN,

    /** Below OR low (and therefore below VAL). */
    BELOW,
}

enum class Phase {
    /** No session has opened yet in the lookback window. */
    WAITING,

    /** Inside the first 30 minutes: the opening range is still being built. */
    FORMING,

    /** Range is set and 5-minute closes are being tracked. */
    ACTIVE,

    /** Session has closed; the snapshot is final until the next session opens. */
    CLOSED,

    /** Session time passed but the feed returned no bars for the opening range. */
    NO_DATA,
}

enum class BreakStatus {
    NONE,
    BREAKOUT_UP,
    BREAKOUT_DOWN,
    FAILED_UP,
    FAILED_DOWN,
    REBREAK_UP,
    REBREAK_DOWN,
}

enum class Dot { GREEN, LIGHT_GREEN, ORANGE, LIGHT_RED, RED, PURPLE, GREY }

enum class EventType(val title: String) {
    RANGE_SET("Opening range set"),
    BREAKOUT_UP("Breakout ↑"),
    BREAKOUT_DOWN("Breakdown ↓"),
    FAILED_UP("FAILED ↑ – back inside range"),
    FAILED_DOWN("FAILED ↓ – back inside range"),
    REBREAK_UP("Re-break ↑"),
    REBREAK_DOWN("Re-break ↓"),
}

/** [time] is the epoch second of the 5-minute candle that triggered the event. */
@Serializable
data class SignalEvent(val time: Long, val type: EventType, val price: Double)

/** Everything the UI needs to draw one session box. */
@Serializable
data class SessionSnapshot(
    val session: SessionId,
    /** Trading date of the session in its market time zone, ISO-8601. */
    val tradingDate: String,
    val openEpoch: Long,
    val rangeEndEpoch: Long,
    val closeEpoch: Long,
    val phase: Phase,
    val orHigh: Double? = null,
    val orLow: Double? = null,
    val vah: Double? = null,
    val valueAreaLow: Double? = null,
    val poc: Double? = null,
    /** Close of the latest completed 5-minute candle after the range. */
    val lastClose: Double? = null,
    val lastCandleTime: Long? = null,
    val zone: Zone? = null,
    val status: BreakStatus = BreakStatus.NONE,
    val dot: Dot = Dot.GREY,
    val above: Int = 0,
    val inside: Int = 0,
    val below: Int = 0,
    val events: List<SignalEvent> = emptyList(),
    /** 5-minute candles from session open, for the mini chart. */
    val candles: List<Bar> = emptyList(),
    val updatedAt: Long = 0,
) {
    val isFinal: Boolean get() = phase == Phase.CLOSED || phase == Phase.NO_DATA
}
