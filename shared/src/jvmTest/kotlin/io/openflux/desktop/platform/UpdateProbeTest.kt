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
    fun `a 403 from the CDN is not a missing build`() {
        val text = missingAssetMessage("v2.3.2", listOf(AssetProbe.Unexpected(403), AssetProbe.Unexpected(403)))
        assertTrue(text.contains("не отдаётся"), text)
    }

    @Test
    fun `an empty probe list does not claim there was no network`() {
        // Nothing was asked, so nothing failed. Blaming the connection would
        // be asserting something nobody observed.
        val text = missingAssetMessage("v2.3.2", emptyList())
        assertTrue(text.contains("не отдаётся"), text)
    }
}