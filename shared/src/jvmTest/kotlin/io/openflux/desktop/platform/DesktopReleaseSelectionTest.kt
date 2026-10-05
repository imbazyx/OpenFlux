package io.openflux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
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
}