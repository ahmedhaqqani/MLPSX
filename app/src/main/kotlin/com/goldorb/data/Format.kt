package com.goldorb.data

import com.goldorb.core.BreakStatus
import com.goldorb.core.Phase
import com.goldorb.core.SessionSnapshot
import com.goldorb.core.Zone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

    fun price(v: Double?): String = v?.let { String.format(Locale.US, "%,.2f", it) } ?: "—"

    fun time(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        time.format(Instant.ofEpochSecond(epoch).atZone(zone))

    fun day(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        day.format(Instant.ofEpochSecond(epoch).atZone(zone))

    fun statusLabel(s: SessionSnapshot): String = when {
        s.phase == Phase.FORMING -> "FORMING RANGE"
        s.phase == Phase.NO_DATA || s.orHigh == null -> "NO DATA"
        else -> when (s.status) {
            BreakStatus.NONE -> "INSIDE RANGE"
            BreakStatus.BREAKOUT_UP -> "BREAKOUT ↑"
            BreakStatus.BREAKOUT_DOWN -> "BREAKDOWN ↓"
            BreakStatus.FAILED_UP -> "FAILED ↑"
            BreakStatus.FAILED_DOWN -> "FAILED ↓"
            BreakStatus.REBREAK_UP -> "RE-BREAK ↑"
            BreakStatus.REBREAK_DOWN -> "RE-BREAK ↓"
        }
    }

    fun zoneLabel(z: Zone?): String = when (z) {
        Zone.ABOVE -> "Above OR high & VAH"
        Zone.LEAN_UP -> "Inside range, above VAH"
        Zone.INSIDE -> "Inside value area"
        Zone.LEAN_DOWN -> "Inside range, below VAL"
        Zone.BELOW -> "Below OR low & VAL"
        null -> "Waiting for first 5m close"
    }
}
