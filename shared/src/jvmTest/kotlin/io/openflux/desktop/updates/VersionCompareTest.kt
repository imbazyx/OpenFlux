package io.openflux.desktop.updates

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The compare is the one thing that must never be wrong: it decides whether a
 * user is offered a downgrade. Every case below is a bug that ships silently -
 * the app would just say "up to date" or, worse, offer the wrong file.
 */
class VersionCompareTest {

    @Test
    fun `numeric parts, not lexicographic`() {
        // The failure this file exists for: "10" < "9" as strings.
        assertTrue(compareVersions("10.0.0", "9.0.0") > 0)
        assertTrue(compareVersions("9.0.0", "10.0.0") < 0)
    }

    @Test
    fun `the v prefix is not part of the version`() {
        assertEquals(0, compareVersions("v2.0.0", "2.0.0"))
        assertEquals(0, compareVersions(" 2.0.0 ", "v2.0.0"))
    }

    @Test
    fun `a pre-release is never offered over the release it precedes`() {
        // Dropping the suffix makes them equal, which is the safe direction:
        // the only question is whether to replace the installed build, and a
        // release candidate of the version already running must not.
        assertEquals(0, compareVersions("2.0.0", "2.0.0-rc1"))
        assertEquals(0, compareVersions("2.0.0-rc1", "2.0.0"))
        // And it still loses to a genuinely newer release.
        assertTrue(compareVersions("2.0.1", "2.0.0-rc1") > 0)
    }

    @Test
    fun `missing parts count as zero, and a short version still compares`() {
        assertEquals(0, compareVersions("2", "2.0.0"))
        assertEquals(0, compareVersions("2.0", "2.0.0"))
        assertTrue(compareVersions("2.1", "2.0.9") > 0)
    }

    @Test
    fun `garbage in a part reads as zero rather than throwing`() {
        assertEquals(0, compareVersions("2.x.0", "2.0.0"))
        assertEquals(0, compareVersions("", "0.0.0"))
    }

    @Test
    fun `the installed build is never offered as an update to itself`() {
        // A release that equals the running version must read as "not newer",
        // or the button stays lit forever on an up-to-date install.
        assertTrue(compareVersions("2.0.0", "2.0.0") <= 0)
        assertTrue(compareVersions("1.2.1", "2.0.0") < 0)
    }

    @Test
    fun `version codes match the gradle rule`() {
        // build.gradle.kts: major*10000 + minor*100 + patch. If this drifts, an
        // update APK is refused by Android with no way for the user to tell why.
        assertEquals(10201, versionCodeOf("1.2.1"))
        assertEquals(20000, versionCodeOf("2.0.0"))
        // The point of moving to 2.0.0: it must clear what a 1.2.1 phone has.
        assertTrue(versionCodeOf("2.0.0") > versionCodeOf("1.2.1"))
    }

    @Test
    fun `the device abi decides which apk is offered`() {
        val names = listOf(
            "OpenFluxAndroid-2.0.0-androidApp-x86-release.apk",
            "OpenFluxAndroid-2.0.0-androidApp-arm64-v8a-release.apk",
            "OpenFluxAndroid-2.0.0-androidApp-universal-release.apk",
        )
        assertEquals(1, pickApk(names, listOf("arm64-v8a")))
        assertEquals(0, pickApk(names, listOf("x86")))
    }

    @Test
    fun `universal is the fallback, and never wins over a real abi`() {
        val names = listOf(
            "OpenFluxAndroid-2.0.0-androidApp-universal-release.apk",
            "OpenFluxAndroid-2.0.0-androidApp-armeabi-v7a-release.apk",
        )
        assertEquals(1, pickApk(names, listOf("armeabi-v7a")))
        assertEquals(0, pickApk(names, listOf("riscv64")))
    }

    @Test
    fun `a device preference order is honoured over the file order`() {
        // "x86" is a substring of "x86_64", so a plain substring match hands
        // an x86-only device the 64-bit build. Whole-token matching is what
        // keeps these two apart.
        val names = listOf("app-x86_64-release.apk", "app-x86-release.apk")
        assertEquals(1, pickApk(names, listOf("x86")))
        assertEquals(0, pickApk(names, listOf("x86_64")))
        // Same trap between the two ARM builds.
        val arm = listOf("app-armeabi-v7a-release.apk", "app-arm64-v8a-release.apk")
        assertEquals(1, pickApk(arm, listOf("arm64-v8a")))
        assertEquals(0, pickApk(arm, listOf("armeabi-v7a")))
    }

    @Test
    fun `nothing to offer yields null rather than the first apk`() {
        // indexOfFirst answers -1 on a miss. Returning that would have the
        // caller index off the front of the asset list, so this asserts the
        // null and not merely "not 0".
        assertNull(pickApk(emptyList(), listOf("arm64-v8a")))
        assertNull(pickApk(listOf("SHA256SUMS.txt", "notes.md"), listOf("arm64-v8a")))
        assertNull(pickApk(listOf("app-riscv64-release.apk"), listOf("arm64-v8a")))
    }
}
