package io.openflux.android.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A core release must not put the app's update button out of reach.
 *
 * GitHub's releases.atom is ordered by CREATION date, not by version, and this
 * repository publishes its exit-node core into the same feed: node-v1.0.0 and
 * 0.0.5 are already in it, carrying no APKs at all. The check used to take the
 * first entry and probe only that one. So the moment any core release was cut,
 * every 2.3.1 user pressing the update button was told the release had no APK
 * for their phone, while the real 2.3.2 - five APKs and counting - sat further
 * down the list and was never examined.
 *
 * The order below is the live feed's actual order, including that v1.2.1 sits
 * ABOVE v2.0.0, which is what makes "first entry means newest" false on its own
 * terms and not merely because of the core releases.
 */
class ReleaseSelectionTest {

    @Test
    fun `a probe cannot outlive the check's own budget`() {
        val now = 1_000_000L
        val deadline = now + 45_000L

        // Whole budget left: the normal case, unchanged from before.
        assertEquals(8_000, AndroidPlatformServices.probeTimeout(now, deadline))

        // Nearly spent. Five ABIs per tag and five tags, at a fixed 8s connect
        // plus 10s read, is 90 seconds inside ONE tag - the 45s ceiling was only
        // consulted between tags, so the check ran to roughly 2x its budget and
        // could report Failed for an update one more tag would have found.
        assertEquals(3_000, AndroidPlatformServices.probeTimeout(now + 42_000, deadline))

        // At the deadline and past it. Zero is not a usable timeout:
        // HttpURLConnection reads 0 as "wait forever", the exact opposite.
        assertEquals(1, AndroidPlatformServices.probeTimeout(now + 45_000, deadline))
        assertEquals(1, AndroidPlatformServices.probeTimeout(now + 600_000, deadline))
    }

    @Test
    fun `the budget covers the request and not each of its two socket phases`() {
        val now = 1_000_000L
        val deadline = now + 45_000L

        // The same value goes to connectTimeout AND readTimeout, so a request
        // can spend it twice. Handing each phase the whole remainder put the
        // worst case at ~49 seconds against a 45 second ceiling - which is the
        // overshoot this was introduced to remove.
        val withThirtySecondsLeft = AndroidPlatformServices.probeTimeout(now + 15_000, deadline)
        assertTrue(
            withThirtySecondsLeft * 2L <= 30_000L,
            "two phases of ${withThirtySecondsLeft}ms exceed what was left",
        )

        // And the sum of every phase of every request still fits the budget.
        val probes = 5 * 5 // five tags, five ABIs
        assertTrue(
            withThirtySecondsLeft.toLong() * probes <= 30_000L,
            "the whole walk cannot fit in what was left",
        )
    }

    private fun entry(tag: String) =
        """  <entry>
    <id>tag:github.com,2008:Repository/12345/$tag</id>
    <link rel="alternate" type="text/html" href="https://github.com/imbazyx/OpenFlux/releases/tag/$tag"/>
  </entry>
"""

    /** Shaped exactly like the real feed, newest-created first. */
    private val liveFeedOrder = buildString {
        append(entry("node-v1.0.0"))
        append(entry("v2.3.1"))
        append(entry("v2.3.0"))
        append(entry("v1.2.1"))
        append(entry("v2.0.0"))
        append(entry("0.0.5"))
        append(entry("v0.1.0"))
    }

    @Test
    fun `a core release is filtered out instead of being offered to the app`() {
        // v0.1.0 stays in: it is a well-formed app version, just long superseded.
        // What must not survive is node-v1.0.0, which has no APKs at all.
        assertEquals(
            listOf("v2.3.1", "v2.3.0", "v2.0.0", "v1.2.1", "v0.1.0"),
            AndroidPlatformServices.appReleaseTags(liveFeedOrder),
        )
    }

    @Test
    fun `app releases come back by version, not by the feed's creation order`() {
        // v1.2.1 is above v2.0.0 in the feed; it must not be above it here.
        val order = AndroidPlatformServices.appReleaseTags(liveFeedOrder)
        assertEquals(listOf("2.3.1", "2.3.0", "2.0.0", "1.2.1", "0.1.0"), order.map { it.removePrefix("v") })
        assertTrue(order.none { it.contains("node") }, "a core tag reached the app list")
    }

    @Test
    fun `a pre-release suffix is not an app release`() {
        // The build only ever publishes X.Y.Z, so a tag like v2.4.0-rc1 has no
        // assets under the name the check would build.
        val feed = entry("v2.4.0-rc1") + entry("v2.3.1")
        assertEquals(listOf("v2.3.1"), AndroidPlatformServices.appReleaseTags(feed))
    }

    @Test
    fun `every tag is read from the link, not from the Atom id`() {
        // The id contains the tag but no releases path; a parser looking for a
        // /releases/tag/ link finds nothing and reports "no update".
        val tags = AndroidPlatformServices.releaseTags(liveFeedOrder)
        assertEquals("node-v1.0.0", tags.first())
        assertEquals(7, tags.size)
    }

    @Test
    fun `a feed with no app release says so rather than naming a core tag`() {
        val onlyCore = entry("node-v2.0.0") + entry("1.0.0") + entry("0.0.5")
        assertEquals(emptyList(), AndroidPlatformServices.appReleaseTags(onlyCore))
    }
}