package org.tadams.nostromo.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TimeTest {

    @Test
    fun secondsToTicks() {
        assertEquals(47, Time.ticks(4.7))
        assertEquals(1, Time.ticks(0.01), "never zero")
    }

    @Test
    fun countdownShowsRealTime() {
        assertEquals("7:00", Time.clock(4200))
        assertEquals("6:59", Time.clock(4190))
        assertEquals("0:01", Time.clock(1), "rounds up, so 0:00 means time is up")
        assertEquals("0:00", Time.clock(0))
    }

    @Test
    fun durationText() {
        assertEquals("4.7s", Time.seconds(47))
    }
}
