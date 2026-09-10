package kira.ditto.upa

enum class UpaPermissionScope {
    Guest,
    Host,
}

enum class UpaPermissionAccess {
    Read,
    Write,
}

data class UpaPermissionGrant(
    val wire: String,
    val scope: UpaPermissionScope,
    val access: UpaPermissionAccess,
) {
    val isHostMutation: Boolean
        get() = scope == UpaPermissionScope.Host && access == UpaPermissionAccess.Write
}

fun classifyUpaPermission(wire: String): UpaPermissionGrant? {
    val value = wire.trim().lowercase()
    UpaPermission.fromWire(value)?.let { permission ->
        return UpaPermissionGrant(permission.wire, permission.scope, permission.access)
    }
    if (!value.startsWith("host.")) return null
    val mutation = HostMutationTokens.any { it in value }
    if (!mutation) return null
    return UpaPermissionGrant(
        wire = value,
        scope = UpaPermissionScope.Host,
        access = UpaPermissionAccess.Write,
    )
}

fun UpaManifest.guestPermissionWires(): List<String> =
    permissions.mapNotNull { wire ->
        classifyUpaPermission(wire)?.takeIf { it.scope == UpaPermissionScope.Guest }?.wire
    }

fun UpaManifest.hostReadPermissionWires(): List<String> =
    permissions.mapNotNull { wire ->
        classifyUpaPermission(wire)
            ?.takeIf { it.scope == UpaPermissionScope.Host && it.access == UpaPermissionAccess.Read }
            ?.wire
    }

fun isForbiddenHostMutation(wire: String): Boolean =
    classifyUpaPermission(wire)?.isHostMutation == true

fun parseReleasedAtEpochMs(value: String): Long? {
    val match = ReleasedAtPattern.matchEntire(value.trim()) ?: return null
    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    val hour = match.groupValues[4].toInt()
    val minute = match.groupValues[5].toInt()
    val second = match.groupValues[6].toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
    val offset = match.groupValues[7]
    val offsetSeconds = if (offset == "Z") {
        0
    } else {
        val sign = if (offset.startsWith("-")) -1 else 1
        val offsetHour = offset.substring(1, 3).toInt()
        val offsetMinute = offset.substring(4, 6).toInt()
        if (offsetHour > 14 || offsetMinute > 59) return null
        sign * (offsetHour * 3600 + offsetMinute * 60)
    }
    val epochDay = epochDay(year, month, day) ?: return null
    return (epochDay * 86_400L + hour * 3600L + minute * 60L + second - offsetSeconds) * 1000L
}

fun isUpaVersionNewer(remote: String, local: String): Boolean {
    val remoteParts = remote.trim().removePrefix("v").substringBefore('-').split('.')
        .mapNotNull { it.toIntOrNull() }
    val localParts = local.trim().removePrefix("v").substringBefore('-').split('.')
        .mapNotNull { it.toIntOrNull() }
    val size = maxOf(remoteParts.size, localParts.size)
    repeat(size) { index ->
        val left = remoteParts.getOrElse(index) { 0 }
        val right = localParts.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}

fun shouldUpdateUpaPlugin(
    localVersion: String,
    localReleasedAt: String,
    remoteVersion: String,
    remoteReleasedAt: String,
): Boolean {
    val remoteTime = parseReleasedAtEpochMs(remoteReleasedAt) ?: return false
    val localTime = parseReleasedAtEpochMs(localReleasedAt) ?: 0L
    if (remoteTime < localTime) return false
    if (isUpaVersionNewer(remoteVersion, localVersion)) return true
    return remoteVersion.trim() == localVersion.trim() && remoteTime > localTime
}

private fun epochDay(year: Int, month: Int, day: Int): Long? {
    val monthDays = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    val feb = if (isLeapYear(year)) 29 else 28
    val maxDay = if (month == 2) feb else monthDays.getOrNull(month - 1) ?: return null
    if (day !in 1..maxDay) return null
    var days = 0L
    if (year >= 1970) {
        for (current in 1970 until year) days += if (isLeapYear(current)) 366 else 365
    } else {
        for (current in year until 1970) days -= if (isLeapYear(current)) 366 else 365
    }
    for (current in 1 until month) {
        days += if (current == 2 && isLeapYear(year)) 29 else monthDays[current - 1]
    }
    days += (day - 1)
    return days
}

private fun isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private val ReleasedAtPattern =
    Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$""")

private val HostMutationTokens = listOf("write", "delete", "remove", "mutate", "uninstall")
