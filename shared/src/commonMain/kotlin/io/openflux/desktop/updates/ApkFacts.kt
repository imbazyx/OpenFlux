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
 */
fun apkRefusal(facts: ApkFacts?, ownPackage: String, installedCode: Long, ownSigner: String?): String? = when {
    facts == null -> "Скачанный файл не читается как приложение Android"
    facts.packageName != ownPackage ->
        "Скачано приложение ${facts.packageName}, а не $ownPackage"
    facts.versionCode < installedCode ->
        "В выпуске versionCode ${facts.versionCode}, он ниже установленного $installedCode — система откажется его ставить"
    ownSigner != null && facts.signer != null && facts.signer != ownSigner ->
        "Выпуск подписан другим ключом, чем установленное приложение"
    else -> null
}