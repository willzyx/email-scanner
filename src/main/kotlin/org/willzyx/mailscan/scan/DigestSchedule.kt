package org.willzyx.mailscan.scan

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The latest moment not after [now] at which the clock in [zone] showed one of [times]: today's when
 * that time of day has come, yesterday's otherwise. [times] must not be empty.
 *
 * A time of day that a clock change skips counts as the moment the clock jumps past it.
 */
fun lastDigestTime(now: Instant, zone: ZoneId, times: List<LocalTime>): Instant {
    val today = now.atZone(zone).toLocalDate()
    return times.maxOf { time ->
        val todays = today.atTime(time).atZone(zone).toInstant()
        if (todays <= now) todays else today.minusDays(1).atTime(time).atZone(zone).toInstant()
    }
}
