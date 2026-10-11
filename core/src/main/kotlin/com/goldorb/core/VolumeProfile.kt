package com.goldorb.core

data class VolumeProfile(
    val low: Double,
    val high: Double,
    val rowSize: Double,
    val rows: DoubleArray,
    val pocIndex: Int,
    val poc: Double,
    val vah: Double,
    val valueAreaLow: Double,
)

/**
 * Fixed-range volume profile over a set of bars, in the style of TradingView's
 * "Fixed Range Volume Profile": the price range is split into [rowCount] rows, each bar's
 * volume is spread evenly over the part of its high-low range that overlaps each row,
 * and the value area grows outward from the POC until it holds [valueAreaPct] of volume.
 */
object VolumeProfileCalc {

    fun compute(bars: List<Bar>, rowCount: Int = 24, valueAreaPct: Double = 0.70): VolumeProfile? {
        if (bars.isEmpty() || rowCount < 1) return null
        val low = bars.minOf { it.l }
        val high = bars.maxOf { it.h }
        if (high <= low) {
            return VolumeProfile(low, high, 0.0, doubleArrayOf(bars.sumOf { it.v }), 0, low, high, low)
        }
        val rowSize = (high - low) / rowCount
        val rows = DoubleArray(rowCount)
        // Feeds with no volume (or all zeros) still get a time-at-price profile.
        val useVolume = bars.any { it.v > 0 }

        for (bar in bars) {
            val vol = if (useVolume) bar.v else 1.0
            if (vol <= 0) continue
            val span = bar.h - bar.l
            if (span <= 0) {
                rows[rowIndex(bar.c, low, rowSize, rowCount)] += vol
                continue
            }
            val first = rowIndex(bar.l, low, rowSize, rowCount)
            val last = rowIndex(bar.h, low, rowSize, rowCount)
            for (i in first..last) {
                val rowLow = low + i * rowSize
                val overlap = minOf(bar.h, rowLow + rowSize) - maxOf(bar.l, rowLow)
                if (overlap > 0) rows[i] += vol * overlap / span
            }
        }

        val pocIndex = pocIndex(rows)
        val total = rows.sum()
        val target = total * valueAreaPct
        var acc = rows[pocIndex]
        var up = pocIndex + 1
        var down = pocIndex - 1
        var top = pocIndex
        var bottom = pocIndex
        while (acc < target && (up < rowCount || down >= 0)) {
            val volUp = if (up < rowCount) rows[up] else -1.0
            val volDown = if (down >= 0) rows[down] else -1.0
            if (volUp >= volDown) {
                acc += volUp; top = up; up++
            } else {
                acc += volDown; bottom = down; down--
            }
        }

        return VolumeProfile(
            low = low,
            high = high,
            rowSize = rowSize,
            rows = rows,
            pocIndex = pocIndex,
            poc = low + (pocIndex + 0.5) * rowSize,
            vah = low + (top + 1) * rowSize,
            valueAreaLow = low + bottom * rowSize,
        )
    }

    private fun rowIndex(price: Double, low: Double, rowSize: Double, rowCount: Int): Int =
        ((price - low) / rowSize).toInt().coerceIn(0, rowCount - 1)

    /** Highest-volume row; ties go to the row closest to the middle of the range. */
    private fun pocIndex(rows: DoubleArray): Int {
        val mid = (rows.size - 1) / 2.0
        var best = 0
        for (i in rows.indices) {
            if (rows[i] > rows[best] ||
                (rows[i] == rows[best] && Math.abs(i - mid) < Math.abs(best - mid))
            ) best = i
        }
        return best
    }
}
