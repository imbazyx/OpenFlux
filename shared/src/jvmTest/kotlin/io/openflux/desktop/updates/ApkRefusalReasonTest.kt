package io.openflux.desktop.updates

import kotlin.test.Test
import kotlin.test.assertEquals
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
}