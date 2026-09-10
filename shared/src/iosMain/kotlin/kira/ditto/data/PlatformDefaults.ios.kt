package kira.ditto.data

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

actual fun platformCurrentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1_000.0).toLong()

actual fun platformUptimeMillis(): Long =
    (NSProcessInfo.processInfo.systemUptime * 1_000.0).toLong()

actual fun platformRandomUuid(): String = NSUUID().UUIDString()

actual fun platformLanguageTag(): String =
    (NSUserDefaults.standardUserDefaults.arrayForKey("AppleLanguages")
        ?.firstOrNull() as? String)
        ?: NSLocale.currentLocale.languageCode

actual fun platformDefaultSystemPrompt(): String = ""

actual fun platformDefaultLlmUserAgent(): String = "Aether/1.0 (iOS)"

actual fun platformDynamicPromptValues(): Map<String, String> {
    val now = NSDate()
    fun format(pattern: String): String = NSDateFormatter().run {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        timeZone = NSTimeZone.localTimeZone
        dateFormat = pattern
        stringFromDate(now)
    }
    return mapOf(
        "current_datetime" to format("yyyy-MM-dd'T'HH:mm:ssXXX"),
        "current_date" to format("yyyy-MM-dd"),
        "current_time" to format("HH:mm:ss"),
        "timezone" to NSTimeZone.localTimeZone.name,
        "unix_timestamp" to now.timeIntervalSince1970.toLong().toString(),
    )
}
