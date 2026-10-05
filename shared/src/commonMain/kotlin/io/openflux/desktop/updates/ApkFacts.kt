package io.openflux.desktop.updates

/**
 * What the downloaded file actually is, as opposed to what it was called.
 *
 * The system installer enforces three things and says nothing about any of them
 * before refusing: the package name, the signing certificate, and that the
 * versionCode is not lower than the installed one. A release built from a
 * different key, or with the version mistyped, therefore fails as a silent
 * refusal inside another app - and this app has no result listener, so the user
 * presses Back to the update screen and is told nothing at all.
 */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val signer: String?,
    /**
     * The APK's own `versionName`, read out of the archive rather than taken
     * from the tag it was downloaded under.
     *
     * `versionCode` alone cannot catch a release cut against a stale
     * `gradle.properties`: tag v2.3.2 with an APK still built at 2.3.1/20301
     * passes `versionCode < installedCode` (equal is not a downgrade), the
     * system installs it, and the app still reports 2.3.1 - so the button
     * offers 2.3.2 again on every press, downloading the same ~40MB for ever
     * with nothing in logcat and nothing on screen. Null when the archive
     * declares no version name, which is not a refusal on its own.
     */
    val versionName: String? = null,
)

/**
 * Why [facts] will not be installed, or null when nothing is known against it.
 *
 * Decided before the hand-off, while the app can still say something useful.
 * Nothing is installed on the strength of this: the system still enforces all
 * three rules. It only lets the app name the cause rather than go quiet.
 *
 * An unknown signer on either side is not a refusal. Android returns no
 * signatures for some archive reads and for some OEM builds, and treating
 * "not known" as "wrong" would block exactly the users who need the button.
 *
 * Same for [ApkFacts.versionName]: known on both sides, and not strictly newer
 * than the installed one, is a release that cannot advance this user and would
 * install the same version again. Refused here, with the two names in the
 * message, because the alternative is the treadmill above - silent, and paid
 * for in downloads. The legitimate upgrade passes: 2.3.2 is strictly newer than
 * 2.3.1 whatever the versionCode pair happens to be.
 */
fun apkRefusal(facts: ApkFacts?, ownPackage: String, installedCode: Long, ownSigner: String?, installedVersionName: String? = null): String? = when {
    facts == null -> "Скачанный файл не читается как приложение Android"
    facts.packageName != ownPackage ->
        "Скачано приложение ${facts.packageName}, а не $ownPackage"
    facts.versionCode < installedCode ->
        "В выпуске versionCode ${facts.versionCode}, он ниже установленного $installedCode — система откажется его ставить"
    ownSigner != null && facts.signer != null && facts.signer != ownSigner ->
        // Ahead of the versionName branch below, deliberately. A release signed
        // with another key cannot be installed at all and the only remedy is to
        // uninstall, losing the user's settings; a version mismatch is merely
        // useless. The versionName branch was placed above this at first and
        // therefore SHADOWED it - a release that was both mis-tagged and
        // mis-signed produced "тег выпуска не совпадает с содержимым APK" and
        // never mentioned the key, sending the user after the wrong problem.
        "Выпуск подписан другим ключом, чем установленное приложение"
    facts.versionName != null && installedVersionName != null &&
        compareVersions(facts.versionName, installedVersionName) <= 0 ->
        "Выпуск собран из APK версии ${facts.versionName}, а установлена ${installedVersionName} — " +
            "обновление ничего не изменит; тег выпуска не совпадает с содержимым APK"
    else -> null
}