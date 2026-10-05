package io.openflux.desktop.updates

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * "No update for this phone" and "the check could not reach GitHub" must not
 * read the same.
 *
 * The check used HEAD and reduced the answer to a boolean, so a timeout and a
 * 404 were indistinguishable. A user on a flaky connection was told the
 * release publishes no APK for their device, concluded there was nothing to
 * install, and never pressed the button again - on the path the owner asked to
 * keep working.
 *
 * This drives the production function, not a copy of it. An earlier version of
 * this test reimplemented the branch locally and therefore proved nothing.
 */
class MissingAssetMessageTest {

    @Test
    fun `every probe unreachable blames the network and tells the user to retry`() {
        val text = missingAssetMessage(
            "v2.3.2",
            listOf(
                AssetProbe.Unreachable("timeout"),
                AssetProbe.Unreachable("timeout"),
                AssetProbe.Unreachable("timeout"),
            ),
        )
        assertTrue(text.contains("GitHub"), text)
        assertTrue(text.contains("попробуйте ещё раз"), text)
        assertTrue(!text.contains("не отдаётся"), "must not blame the release: $text")
        assertTrue(text.contains("v2.3.2"), "the tag is what the user can check: $text")
    }

    @Test
    fun `all probes 404 blames the release and does not send the user in circles`() {
        val text = missingAssetMessage(
            "v2.3.2",
            listOf(AssetProbe.Absent, AssetProbe.Absent, AssetProbe.Absent),
        )
        assertTrue(text.contains("не отдаётся"), text)
        assertTrue(!text.contains("GitHub"), "must not blame the network: $text")
        assertTrue(!text.contains("попробуйте ещё раз"), text)
    }

    @Test
    fun `a partial failure is treated as a missing build, never as the network`() {
        // One 404 next to a timeout means the release answered for some ABIs,
        // so "no connection" would be the less true statement.
        val text = missingAssetMessage("v2.3.2", listOf(AssetProbe.Absent, AssetProbe.Unreachable("timeout")))
        assertTrue(text.contains("не отдаётся"), text)
        assertTrue(!text.contains("GitHub"), "must not blame the network: $text")
    }

    @Test
    fun `a 403 is not reported as the release withholding the APK`() {
        // A 403 or 429 from the CDN says something about the way we asked, not
        // about what the release contains. The old code answered "не отдаётся"
        // for it, and a test then pinned that wrong sentence in place.
        val text = missingAssetMessage("v2.3.2", listOf(AssetProbe.Unexpected(403), AssetProbe.Unexpected(403)))
        assertTrue(!text.contains("не отдаётся"), "must not blame the release: $text")
        assertTrue(text.contains("403"), "the user should see the status: $text")
        assertTrue(text.contains("Попробуйте позже"), "and be told to retry: $text")
    }

    @Test
    fun `an empty probe list claims nothing at all`() {
        // Nothing was asked, so nothing failed. Either version of the story
        // would be asserting something nobody observed.
        val text = missingAssetMessage("v2.3.2", emptyList())
        assertTrue(!text.contains("не отдаётся"), text)
        assertTrue(!text.contains("GitHub"), text)
    }

    @Test
    fun `only a definite 404 across every ABI blames the release`() {
        val text = missingAssetMessage("v2.3.2", listOf(AssetProbe.Absent, AssetProbe.Absent))
        assertTrue(text.contains("не отдаётся"), text)
        assertTrue(!text.contains("GitHub"), "must not blame the network: $text")
    }
}