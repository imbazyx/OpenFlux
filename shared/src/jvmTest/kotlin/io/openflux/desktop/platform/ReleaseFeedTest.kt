package io.openflux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The update check reads GitHub's releases Atom feed.
 *
 * It used to read the REST API instead, which is limited per IP address: the
 * answer was a 403, and the app showed "не проверить" - the same thing it shows
 * when there is genuinely nothing new. The feed has no such limit, and this is
 * the part of the new path that can quietly return nothing, so it is pinned
 * here against a real feed body.
 */
class ReleaseFeedTest {

    /** A trimmed copy of what github.com returns, structure unchanged. */
    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en-US">
          <entry>
            <id>tag:github.com,2008:Repository/1392288685/v2.1.0</id>
            <link type="text/html" rel="alternate" href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.1.0"/>
            <title>OpenFlux v2.1.0</title>
          </entry>
          <entry>
            <id>tag:github.com,2008:Repository/1392288685/v2.0.0</id>
            <link type="text/html" rel="alternate" href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.0.0"/>
            <title>OpenFlux v2.0.0</title>
          </entry>
        </feed>
    """.trimIndent()

    private val services = JvmPlatformServices("2.1.0") { "test" }

    @Test
    fun `takes the first entry, which is the newest`() {
        assertEquals("v2.1.0", JvmPlatformServices.newestTag(feed))
    }

    @Test
    fun `the entry id alone is not enough to find the tag`() {
        // The id is `tag:github.com,2008:Repository/<id>/v2.1.0`: no releases
        // path, and a different repository id in every fork. A parser that looks
        // for it produces nothing, which is how a working feed reads as a failed
        // check.
        val idOnly = """<entry><id>tag:github.com,2008:Repository/1392288685/v9.9.9</id></entry>"""
        assertNull(JvmPlatformServices.newestTag(idOnly))
    }

    @Test
    fun `a feed with no entries yields nothing rather than a wrong version`() {
        val empty = """<feed xmlns="http://www.w3.org/2005/Atom"></feed>"""
        assertNull(JvmPlatformServices.newestTag(empty))
    }

    @Test
    fun `an error page is not mistaken for a release`() {
        // GitHub answers a rate limit with HTML, not with this document. Reading
        // a tag out of it would invent a version rather than admit defeat.
        val html = "<html><body>API rate limit exceeded</body></html>"
        assertNull(JvmPlatformServices.newestTag(html))
    }
}
