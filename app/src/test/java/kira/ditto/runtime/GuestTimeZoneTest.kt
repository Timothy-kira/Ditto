package kira.ditto.runtime

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The POSIX sign is inverted, which is exactly the kind of detail that reads correct and is not.
 * These pin the convention rather than the formatting.
 */
class GuestTimeZoneTest {
    private fun at(zone: String, iso: String): Long =
        ZonedDateTime.parse(iso).withZoneSameInstant(ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun zoneAheadOfUtcGetsNegativeOffset() {
        // The reported bug: host UTC+8, guest UTC, dates disagreed every evening.
        assertEquals(
            "<+08>-8",
            posixTimeZoneSpec(ZoneId.of("Asia/Shanghai"), at("UTC", "2026-09-09T18:08:00Z")),
        )
    }

    @Test
    fun zoneBehindUtcGetsPositiveOffset() {
        assertEquals(
            "<-05>+5",
            posixTimeZoneSpec(ZoneId.of("America/New_York"), at("UTC", "2026-01-15T12:00:00Z")),
        )
    }

    @Test
    fun daylightSavingUsesTheOffsetInEffectAtThatInstant() {
        // Same zone, six months apart: the offset must follow the instant, not the zone's default.
        assertEquals(
            "<-04>+4",
            posixTimeZoneSpec(ZoneId.of("America/New_York"), at("UTC", "2026-07-15T12:00:00Z")),
        )
    }

    @Test
    fun halfHourZoneKeepsMinutes() {
        assertEquals(
            "<+0530>-5:30",
            posixTimeZoneSpec(ZoneId.of("Asia/Kolkata"), at("UTC", "2026-09-09T18:08:00Z")),
        )
    }

    @Test
    fun utcIsSpelledWithoutAnOffset() {
        assertEquals("UTC0", posixTimeZoneSpec(ZoneId.of("UTC"), at("UTC", "2026-09-09T18:08:00Z")))
    }
}
