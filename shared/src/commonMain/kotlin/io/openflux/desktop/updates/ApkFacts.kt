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
fun apkRefusal(facts: ApkFacts?, ownPackage: String, installedCode: Long, ownSigner: String?, installedVersionName: String? = null, taggedVersion: String? = null): String? = when {
    facts == null -> "Скачанный файл не читается как приложение Android"
    facts.packageName != ownPackage ->
        "Скачано приложение ${facts.packageName}, а не $ownPackage"
    // Kept BELOW the signer branch on purpose. It was above it, and shadowed it
    // the same way the versionName branch did one commit earlier: an APK that
    // was both mis-signed and carried a lower versionCode named the
    // versionCode, sending the user after the wrong problem. Same defect, one
    // branch higher than the one that was fixed.
    facts.versionCode < installedCode ->
        "В выпуске versionCode ${facts.versionCode}, он ниже установленного $installedCode — система откажется его ставить"
    ownSigner != null && facts.signer != null && facts.signer != ownSigner ->
        "Выпуск подписан другим ключом, чем установленное приложение"
    // Archive against the TAG, which is what the message claims. Comparing only
    // against the installed app misses the case this was written for: a user on
    // 2.3.0 offered tag v2.3.2 whose APK was built stale at 2.3.1 gets
    // compareVersions("2.3.1","2.3.0") = +1, so nothing refuses. The system
    // sees 20301 > 20300 and installs it, the app then reports 2.3.1, the tag
    // v2.3.2 is still newer, and the button offers it again - the same 40MB for
    // ever. Only comparing the archive to the tag it was published under sees
    // the divergence at all.
    //
    // AFTER the signer branch, and deliberately: this exact branch was placed
    // above that check once and shadowed it, sending users after the wrong
    // problem when a release was both mis-tagged and mis-signed.
    facts.versionName != null && taggedVersion != null &&
        compareVersions(facts.versionName, taggedVersion) != 0 ->
        "Выпуск $taggedVersion собран из APK версии ${facts.versionName} — тег выпуска не совпадает с содержимым APK"
    facts.versionName != null && installedVersionName != null &&
        compareVersions(facts.versionName, installedVersionName) <= 0 ->
        "Выпуск собран из APK версии ${facts.versionName}, а установлена ${installedVersionName} — " +
            "обновление ничего не изменит"
    else -> null
}