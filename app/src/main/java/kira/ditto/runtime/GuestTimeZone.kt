package kira.ditto.runtime

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * The guest's clock, expressed the only way the guest can understand it.
 *
 * The Alpine rootfs ships without tzdata - there is no `/usr/share/zoneinfo` and no
 * `/etc/localtime` - so musl cannot resolve an IANA name like `Asia/Shanghai`. What it does
 * support is the POSIX `TZ` string, which carries the offset inline and needs no files.
 *
 * This matters because the guest is where the CLI decides what "today" is. Running it in UTC
 * while the host sat at UTC+8 put two different dates in the same prompt - the host's injected
 * `[环境] 今天是 2026-09-10` next to the CLI's `Today's date is 2026-09-09` - and the model
 * believed the CLI. For any zone east of UTC that disagreement covers every evening.
 *
 * The rule here is simply "whatever the phone reads right now": the offset in effect at this
 * instant, with no DST transition rules attached. Encoding transitions would let the guest
 * follow a change on its own, but it cannot anyway - musl needs tzdata to name the zone, and
 * shipping tzdata to fix a clock the host already knows is the wrong trade. Instead the offset
 * is recomputed on every guest process launch (this is called from
 * `buildAlpineProcessEnvironment`), so a DST change lands on the next launch.
 *
 * POSIX sign convention is inverted from the usual one: a zone *ahead* of UTC gets a *negative*
 * offset, so UTC+8 is written `-8`. The abbreviation uses the `<...>` quoting form because a
 * numeric abbreviation like `+08` is not a valid bare token.
 */
internal fun posixTimeZoneSpec(
    zone: ZoneId = ZoneId.systemDefault(),
    nowMillis: Long = System.currentTimeMillis(),
): String {
    val totalSeconds = zone.rules.getOffset(Instant.ofEpochMilli(nowMillis)).totalSeconds
    if (totalSeconds == 0) return "UTC0"

    val hours = abs(totalSeconds) / 3600
    val minutes = (abs(totalSeconds) % 3600) / 60
    val seconds = abs(totalSeconds) % 60

    // The name carries the real sign; the offset field carries the inverted POSIX one.
    val nameSign = if (totalSeconds > 0) "+" else "-"
    val name = buildString {
        append('<').append(nameSign)
        append("%02d".format(hours))
        if (minutes != 0 || seconds != 0) append("%02d".format(minutes))
        append('>')
    }

    val offset = buildString {
        append(if (totalSeconds > 0) "-" else "+")
        append(hours)
        if (minutes != 0 || seconds != 0) append(":%02d".format(minutes))
        if (seconds != 0) append(":%02d".format(seconds))
    }

    return name + offset
}
