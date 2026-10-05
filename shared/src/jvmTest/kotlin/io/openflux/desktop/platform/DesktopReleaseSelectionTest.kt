package io.openflux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A core release must not put the desktop update button out of reach.
 *
 * The same defect the Android side had, in the same repository, in the same
 * feed. `checkForUpdateDetailed()` took `newestTag(feed)` - the first Atom
 * entry, which is the newest by CREATION date - while this repository also
 * publishes its exit-node core releases there. `node-v1.0.0` and `0.0.5` are
 * already in the live feed carrying no MSI at all.
 *
 * So the moment any core release was cut, the desktop updater reported
 * "выпуск node-v1.0.0 есть, но установщик OpenFlux-node-v1.0.0.msi не отдаётся"
 * and, having no walk down the list, stayed broken for every desktop user until
 * the next app release. Android got `appReleaseTags()` first; desktop got
 * neither the filter nor the walk.
 *
 * The feed below is the live order, including v1.2.1 sitting ABOVE v2.0.0,
 * which is what makes "first entry means newest" false on its own terms.
 */
class DesktopReleaseSelectionTest {

    private val liveFeed = """
        <feed>
          <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.3.1"/></entry>
          <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.3.0"/></entry>
          <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.0.0"/></entry>
          <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v1.2.1"/></entry>
          <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/node-v1.0.0"/></entry>
          <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/0.0.5"/></entry>
        </feed>
    """.trimIndent()

    @Test
    fun `core releases are excluded from the app list`() {
        val tags = JvmPlatformServices.appReleaseTags(liveFeed)

        assertTrue(tags.none { it.startsWith("node-") }, "a core release reached the app list: $tags")
        assertTrue(tags.none { it == "0.0.5" }, "a bare core version reached the app list: $tags")
        assertEquals(listOf("v2.3.1", "v2.3.0", "v2.0.0", "v1.2.1"), tags)
    }

    @Test
    fun `the app list is newest version first, not newest created`() {
        // v1.2.1 is created after v2.0.0 in the live feed. Sorting by version
        // puts 2.0.0 ahead of it; taking the first entry would not.
        val tags = JvmPlatformServices.appReleaseTags(liveFeed)
        assertTrue(
            tags.indexOf("v2.0.0") < tags.indexOf("v1.2.1"),
            "sorted by creation date instead of version: $tags",
        )
    }

    @Test
    fun `an empty app list is reported as such rather than as a core release`() {
        val coreOnly = """
            <feed>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/node-v2.0.0"/></entry>
            </feed>
        """.trimIndent()

        assertEquals(emptyList(), JvmPlatformServices.appReleaseTags(coreOnly))
    }

    @Test
    fun `a successful HEAD has no body and must still count as reachable`() {
        // The bug this pins: Answer.ok was derived from the body, and request()
        // forces the body to null for a HEAD - which has no body by definition.
        // So every successful probe of a release asset scored as a failure, the
        // walk over the newest app releases could never find one, and the user
        // was told
        //
        //   выпуск v2.3.2 есть, но установщик OpenFlux-2.3.2.msi не отдаётся: null
        //
        // about an asset that had just answered 200, with a literal `null`
        // where the reason should have been.
        //
        // Reachability is "no problem", never "there is a body". These are the
        // REAL Answer instances request() builds, not a copy of its logic.
        assertTrue(JvmPlatformServices.Answer(body = null, problem = null).ok, "a 200 HEAD carries no body")
        assertFalse(JvmPlatformServices.Answer(body = null, problem = "на GitHub нет такого файла").ok)
        // And a body without a problem is still fine - the feed path.
        assertTrue(JvmPlatformServices.Answer(body = "<feed/>", problem = null).ok)
    }

    @Test
    fun `sorting is by version and not by the text of the joined digits`() {
        // The first version of this comparator joined the components into one
        // string, which is a TEXT sort. "2.3.10" then sits below "2.3.1", and
        // "10.0.0" below "9.0.0". It looked right for every version this
        // project has shipped, because all of them have single-digit components
        // and the key has always been exactly three characters - which is how a
        // landmine at 2.10.0 stayed invisible.
        val wide = """
            <feed>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.3.1"/></entry>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.9.0"/></entry>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.3.10"/></entry>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v9.0.0"/></entry>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v10.0.0"/></entry>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.10.0"/></entry>
            </feed>
        """.trimIndent()

        assertEquals(
            listOf("v10.0.0", "v9.0.0", "v2.10.0", "v2.9.0", "v2.3.10", "v2.3.1"),
            JvmPlatformServices.appReleaseTags(wide),
        )
    }

    @Test
    fun `a pre-release tag is not an app release`() {
        // "Starts with v and is longer than v" also accepts v2.4.0-rc1, and
        // versionParts drops the suffix, so such a tag sorted ABOVE v2.3.2, took
        // the first walk slot, failed its probe, and the user was told "выпуск
        // v2.4.0-rc1 есть, но установщик OpenFlux-2.4.0.msi не отдаётся" about
        // a tag this project publishes no assets for. Android's filter is a
        // regex with a test; the desktop's was a prefix check and had neither.
        val withRc = """
            <feed>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.4.0-rc1"/></entry>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.3.2"/></entry>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/v2.3"/></entry>
              <entry><link href="https://github.com/imbazyx/OpenFlux/releases/tag/node-v1.0.0"/></entry>
            </feed>
        """.trimIndent()

        assertEquals(listOf("v2.3.2"), JvmPlatformServices.appReleaseTags(withRc))
    }
}