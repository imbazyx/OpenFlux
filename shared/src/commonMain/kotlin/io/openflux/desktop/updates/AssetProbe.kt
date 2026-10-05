package io.openflux.desktop.updates

/**
 * What one reachability probe of a release asset established.
 *
 * The old check collapsed this to a boolean, so a HEAD request that timed out
 * and one that came back 404 looked identical to the caller.
 */
sealed interface AssetProbe {
    data object Found : AssetProbe
    data object Absent : AssetProbe

    /** The server answered, but not with something usable. */
    data class Unexpected(val code: Int) : AssetProbe

    /** The request never got an answer: no network, DNS, timeout, TLS. */
    data class Unreachable(val reason: String) : AssetProbe
}

/**
 * The message for a release whose APK could not be located on any ABI.
 *
 * "The release publishes nothing for this phone" and "we never reached GitHub"
 * are different facts with different remedies, and a user shown the first one
 * concludes there is nothing to install and never tries again. That is why this
 * lives in shared code with a test rather than inside the Android file: the
 * distinction is the whole point of the check, so it has to be exercised by
 * something other than reading.
 *
 * Only when *every* probe failed to connect is the network blamed. One 404
 * alongside a timeout means the release answered for some ABI, and "no
 * connection" would then be the less true of the two statements.
 */
fun missingAssetMessage(tag: String, probes: List<AssetProbe>): String {
    val reasons = probes.mapNotNull { (it as? AssetProbe.Unreachable)?.reason }
    return if (reasons.size == probes.size && reasons.isNotEmpty()) {
        "не удалось проверить выпуск $tag: нет связи с GitHub (${reasons.first()}). " +
            "Это не значит, что обновления нет — попробуйте ещё раз."
    } else {
        "выпуск $tag есть, но APK для этого устройства не отдаётся"
    }
}