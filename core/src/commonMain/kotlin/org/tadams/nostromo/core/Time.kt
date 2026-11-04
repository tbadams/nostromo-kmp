package org.tadams.nostromo.core

import kotlin.math.roundToInt

/** The simulation runs at a fixed rate; durations in data are seconds, converted here. */
object Time {
    /** Fixed simulation rate: one tick is 100 ms. */
    const val TICKS_PER_SECOND = 10

    /** Seconds → ticks, at least one tick. */
    fun ticks(seconds: Double): Int = (seconds * TICKS_PER_SECOND).roundToInt().coerceAtLeast(1)

    /** Ticks remaining → countdown text "m:ss", rounding up so it reads 0:00 only when time is up. */
    fun clock(ticksRemaining: Long): String {
        val seconds = (ticksRemaining.coerceAtLeast(0) + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    /** Ticks → duration text like "4.7s". */
    fun seconds(ticks: Int): String = "${ticks / TICKS_PER_SECOND}.${ticks % TICKS_PER_SECOND}s"
}
