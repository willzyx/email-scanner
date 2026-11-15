package org.willzyx.mailscan.scan

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two hours ahead of UTC in winter and three in summer. */
private val NICOSIA: ZoneId = ZoneId.of("Asia/Nicosia")
private val MORNING_AND_EVENING = listOf(LocalTime.of(9, 0), LocalTime.of(21, 0))

/** The moment the clock in Nicosia shows [text], as in `2026-11-03T10:00`. */
private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(NICOSIA).toInstant()

class DigestScheduleTest {
    private fun last(now: String, times: List<LocalTime> = MORNING_AND_EVENING): Instant =
        lastDigestTime(at(now), NICOSIA, times)

    @Test
    fun `between the two times of a day it is the earlier one`() {
        assertEquals(at("2026-11-03T09:00"), last("2026-11-03T10:00"))
        assertEquals(at("2026-11-03T09:00"), last("2026-11-03T20:59:59"))
    }

    @Test
    fun `after the last time of a day it is that one`() {
        assertEquals(at("2026-11-03T21:00"), last("2026-11-03T21:30"))
        assertEquals(at("2026-11-03T21:00"), last("2026-11-03T23:59:59"))
    }

    @Test
    fun `before the first time of a day it is the last one of the day before`() {
        assertEquals(at("2026-11-02T21:00"), last("2026-11-03T00:00"))
        assertEquals(at("2026-11-02T21:00"), last("2026-11-03T08:59:59"))
    }

    @Test
    fun `a time counts from the very moment it comes`() {
        assertEquals(at("2026-11-03T09:00"), last("2026-11-03T09:00"))
        assertEquals(at("2026-11-03T21:00"), last("2026-11-03T21:00"))
    }

    @Test
    fun `the order the times are listed in does not matter`() {
        assertEquals(at("2026-11-03T09:00"), last("2026-11-03T10:00", MORNING_AND_EVENING.reversed()))
    }

    @Test
    fun `with one time a day it is today's or yesterday's`() {
        val noon = listOf(LocalTime.NOON)
        assertEquals(at("2026-11-03T12:00"), last("2026-11-03T12:01", noon))
        assertEquals(at("2026-11-02T12:00"), last("2026-11-03T11:59", noon))
    }

    @Test
    fun `the time before one is found by asking again a moment earlier`() {
        val evening = last("2026-11-03T22:00")
        val morning = lastDigestTime(evening.minusSeconds(1), NICOSIA, MORNING_AND_EVENING)
        val eveningBefore = lastDigestTime(morning.minusSeconds(1), NICOSIA, MORNING_AND_EVENING)

        assertEquals(at("2026-11-03T09:00"), morning)
        assertEquals(at("2026-11-02T21:00"), eveningBefore)
    }

    @Test
    fun `the times are those of the zone's clock, in winter and in summer`() {
        assertEquals(Instant.parse("2026-11-03T07:00:00Z"), last("2026-11-03T10:00"))
        assertEquals(Instant.parse("2026-07-03T06:00:00Z"), last("2026-07-03T10:00"))
    }

    @Test
    fun `a time the clock skips when it goes forward counts as the moment of the jump`() {
        // On 29 March 2026 the clock in Nicosia goes from 03:00 straight to 04:00.
        val skipped = listOf(LocalTime.of(3, 30))
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), last("2026-03-29T05:00", skipped))
        assertEquals(at("2026-03-28T03:30"), last("2026-03-29T02:59", skipped))
    }
}
