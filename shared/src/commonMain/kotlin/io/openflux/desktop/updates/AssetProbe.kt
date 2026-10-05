package io.openflux.desktop.updates

/**
 * What one reachability probe of a release asset established.
 *
 * The old check collapsed this to a boolean, so a HEAD request that timed out
 * and one that came back 404 looked identical to the caller.
 */
sealed interface AssetProbe {
    data object Found : AssetProbe

    /** 404: the release exists, this file is not on it. */
    data object Absent : AssetProbe

    /** The server answered, but not with a yes or a no we can act on. */
    data class Unexpected(val code: Int) : AssetProbe

    /** The request never got an answer: no network, DNS, timeout, TLS. */
    data class Unreachable(val reason: String) : AssetProbe
}

/**
 * The message for a release whose APK could not be located on any ABI.
 *
 * Three different facts used to produce one sentence. "The release publishes
 * nothing for this phone" is a statement about the release, and a user shown it
 * concludes there is nothing to install and never presses the button again -
 * which is precisely the path the owner asked to keep working.
 *
 * Only a definite 404 across every ABI is a statement about the release.
 * Anything else - a timeout, a 403 from the CDN, a 429 from rate limiting, a
 * 5xx - says something about the way we asked, and saying otherwise is an
 * invention. Those get their own wording, and it tells the user to try again
 * rather than to give up.
 */
fun missingAssetMessage(tag: String, probes: List<AssetProbe>): String {
    if (probes.isEmpty()) return "не удалось проверить выпуск $tag: попробуйте ещё раз"

    val unreachable = probes.mapNotNull { (it as? AssetProbe.Unreachable)?.reason }
    if (unreachable.size == probes.size) {
        return "не удалось проверить выпуск $tag: нет связи с GitHub (${unreachable.first()}). " +
            "Это не значит, что обновления нет — попробуйте ещё раз."
    }

    val codes = probes.mapNotNull { (it as? AssetProbe.Unexpected)?.code }.distinct()
    if (codes.isNotEmpty()) {
        return "не удалось проверить выпуск $tag: GitHub ответил кодом ${codes.joinToString(", ")}. " +
            "Попробуйте позже."
    }

    return "выпуск $tag есть, но APK для этого устройства не отдаётся"
}