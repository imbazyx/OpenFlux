package io.openflux.desktop.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Why an APK will be refused must be decided before the installer is opened.
 *
 * Android enforces three things silently: the package name, the signing
 * certificate, and that the versionCode is not lower than what is installed. A
 * release built from another key, or with the version mistyped, therefore fails
 * as a refusal inside a different app - and this app has no result listener, so
 * the user comes back to the update screen and is told nothing at all. Deciding
 * it here is what lets the app name the cause instead of going quiet.
 *
 * This drives the production function. An earlier version reimplemented the
 * branch locally and therefore proved nothing.
 */
class ApkRefusalTest {

    private val own = "aa:bb"
    private val pkg = "io.openflux.client"

    private fun refuse(code: Long, signer: String?, installed: Long = 20301) =
        apkRefusal(ApkFacts(pkg, code, signer), pkg, installed, own)

    @Test
    fun `a matching release is passed through`() {
        assertNull(refuse(20302, own))
    }

    @Test
    fun `a release signed with another key is refused`() {
        assertEquals(
            "Выпуск подписан другим ключом, чем установленное приложение",
            refuse(20302, "cc:dd"),
        )
    }

    @Test
    fun `a lower versionCode is refused before the system gets a chance`() {
        // A mistyped version in a release tag is enough: the installer would
        // reject it without a word.
        assertEquals(
            "В выпуске versionCode 20301, он ниже установленного 20302 — система откажется его ставить",
            refuse(20301, own, installed = 20302),
        )
    }

    @Test
    fun `the same versionCode is not a downgrade`() {
        assertNull(refuse(20302, own, installed = 20302))
    }

    @Test
    fun `a different package is refused`() {
        assertEquals(
            "Скачано приложение com.other.app, а не io.openflux.client",
            apkRefusal(ApkFacts("com.other.app", 99999, own), pkg, 20301, own),
        )
    }

    @Test
    fun `an unreadable file is refused rather than handed over`() {
        assertEquals(
            "Скачанный файл не читается как приложение Android",
            apkRefusal(null, pkg, 20301, own),
        )
    }

    @Test
    fun `an unknown signer on the release does not block it`() {
        // Android returns no signatures for some archive reads. Not knowing is
        // not the same as it being wrong, and refusing every such release would
        // break the button for exactly the users who need it.
        //
        // This case is only meaningful because readApk now asks for
        // GET_SIGNATURES. It used to pass flags=0, which makes
        // PackageManagerService skip certificate collection entirely, so the
        // signer was ALWAYS null on this path and this assertion held for every
        // input - it was pinning the defect rather than the behaviour.
        assertNull(refuse(20302, null))
    }

    @Test
    fun `an unknown signer on the running app does not block it`() {
        assertNull(apkRefusal(ApkFacts(pkg, 20302, "cc:dd"), pkg, 20301, null))
    }

    @Test
    fun `the package is named before the key, because it is the actionable one`() {
        val text = apkRefusal(ApkFacts("com.fork.app", 1, "zz"), pkg, 20301, own)!!
        assertEquals(true, text.contains("com.fork.app"), text)
    }

    @Test
    fun `an APK carrying the same version as the installed app is a treadmill`() {
        // The release was tagged v2.3.2 but the APK in it was built from a stale
        // gradle.properties and still says 2.3.1/20301. Equal versionCode is not
        // a downgrade, so PackageInstaller accepts it, the app still reports
        // 2.3.1, and the button offers 2.3.2 again on every press - the same
        // ~40MB for ever, with nothing on screen and nothing in logcat. Only the
        // versionName read out of the archive can see this.
        val stale = ApkFacts(pkg, 20301, own, versionName = "2.3.1")

        val text = apkRefusal(stale, pkg, 20301, own, installedVersionName = "2.3.1")
        assertNotNull(text, "a release that cannot advance the user must be named, not installed")
        assertEquals(true, text!!.contains("2.3.1"), text)
    }

    @Test
    fun `a same-versionCode upgrade whose APK really is newer still passes`() {
        // The owner's own case: 2.3.1 installed, v2.3.2 released. The codes are
        // equal by design - 2.3.2 is 20302 for everyone - and this guard must
        // not turn the update button the owner depends on into a refusal.
        assertNull(apkRefusal(ApkFacts(pkg, 20302, own, "2.3.2"), pkg, 20301, own, "2.3.1"))
    }

    @Test
    fun `an unknown version on either side is not a refusal`() {
        // Android declines to fill versionName for some archives and some OEM
        // builds. Treating "not known" as "wrong" would block exactly the users
        // who need the button - the same reasoning as the unknown signer.
        assertNull(apkRefusal(ApkFacts(pkg, 20302, own), pkg, 20301, own, "2.3.1"))
        assertNull(apkRefusal(ApkFacts(pkg, 20302, own, "2.3.2"), pkg, 20301, own, null))
    }
}