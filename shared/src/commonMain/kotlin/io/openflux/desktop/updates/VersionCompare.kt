package io.openflux.desktop.updates

/**
 * Compares two "X.Y.Z" versions.
 *
 * Numeric parts, not a string compare: "10.0.0" is newer than "9.0.0" and a
 * lexicographic compare says the opposite, which would offer a downgrade as
 * an update.
 *
 * A pre-release suffix is dropped, so "2.0.0-rc1" and "2.0.0" compare equal.
 * That is deliberate and conservative: the question this answers is only
 * "should this build be replaced", and a pre-release of the version already
 * installed is not. Reading it as older instead would let the app offer a
 * release candidate to a user who is already on the final.
 */
fun compareVersions(a: String, b: String): Int {
    val left = versionParts(a)
    val right = versionParts(b)
    for (i in 0 until maxOf(left.size, right.size)) {
        val l = left.getOrElse(i) { 0 }
        val r = right.getOrElse(i) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

/** "v2.0.0" and "2.0.0 " both give [0, 2, 0]. */
internal fun versionParts(v: String): List<Int> {
    val core = v.trim().removePrefix("v").substringBefore('-')
    return core.split('.').map { part ->
        part.filter { it.isDigit() }.toIntOrNull() ?: 0
    }
}

/** Major*10000 + minor*100 + patch, the scheme build.gradle.kts uses. */
fun versionCodeOf(v: String): Int {
    val p = versionParts(v)
    return (p.getOrElse(0) { 0 }) * 10000 + (p.getOrElse(1) { 0 }) * 100 + (p.getOrElse(2) { 0 })
}

/**
 * Picks the APK to install on this device: the first release asset whose ABI
 * the device supports, else the universal one.
 *
 * The order of [supportedAbis] is the device's own preference, so a phone able
 * to run both arm64 and v7a is offered arm64. Returns null when nothing
 * matches, which the caller must treat as "no update": indexOfFirst answers -1
 * for a miss, and passing that on as an index would index off the front of the
 * asset list.
 */
fun pickApk(assetNames: List<String>, supportedAbis: List<String>): Int? {
    for (abi in supportedAbis.map { it.lowercase() }) {
        val i = assetNames.indexOfFirst { name -> hasAbi(name, abi) }
        if (i >= 0) return i
    }
    return assetNames.indexOfFirst { name ->
        assetAbis(name).contains("universal")
    }.takeIf { it >= 0 }
}

/** Every known ABI the file name mentions, matched whole rather than by substring. */
internal fun assetAbis(fileName: String): Set<String> {
    val lower = fileName.lowercase()
    if (!lower.endsWith(".apk")) return emptySet()
    return KNOWN_ABIS.filter { hasAbi(lower, it) }.toSet()
}

/**
 * Whether [fileName] is an APK for [abi].
 *
 * A plain substring test is wrong in both directions: "x86" sits inside
 * "x86_64", so an x86-only device would be handed the 64-bit build, and
 * splitting the name on dashes tears "arm64-v8a" into two pieces that are
 * not ABIs at all. So the match must be bounded - it may not run into a
 * further ABI character, which is what separates "x86" from "x86_64" and
 * "arm64" from "arm64-v8a".
 */
private fun hasAbi(fileName: String, abi: String): Boolean {
    val lower = fileName.lowercase()
    if (!lower.endsWith(".apk")) return false
    return abiBoundary(abi).containsMatchIn(lower)
}

private val abiPatterns = mutableMapOf<String, Regex>()

private fun abiBoundary(abi: String): Regex = abiPatterns.getOrPut(abi) {
    // "arm64-v8a" inside "...-arm64-v8a-release.apk": the character before the
    // match is "-", the one after is "-", neither continues the ABI.
    Regex("(?<![a-z0-9])(?:" + abi.split('-').joinToString("[-_]") { Regex.escape(it) } + ")(?![a-z0-9_])")
}

private val KNOWN_ABIS = listOf(
    "arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "universal",
)
