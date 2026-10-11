package io.openflux.desktop.platform

import io.openflux.desktop.platform.ReleaseNotes.from
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseNotesTest {

    private fun entry(tag: String, body: String) =
        """
        <entry>
          <id>tag:github.com,2008:Repository/1/$tag</id>
          <link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/$tag"/>
          <title>$tag</title>
          <content type="html">$body</content>
        </entry>
        """.trimIndent()

    @Test
    fun readsTheBodyOfTheNamedTag() {
        val feed = "<feed>" + entry("v2.3.3", "&lt;p&gt;Правка 409&lt;/p&gt;") + entry("v2.3.2", "старое") + "</feed>"
        assertEquals("Правка 409", from(feed, "v2.3.3"))
    }

    @Test
    fun keepsListsReadable() {
        // A release note is mostly a list. Dropping the bullets would leave one
        // run-on sentence, which is why <li> becomes "• " instead of nothing.
        val body = "&lt;ul&gt;&lt;li&gt;первое&lt;/li&gt;&lt;li&gt;второе&lt;/li&gt;&lt;/ul&gt;"
        val text = from("<feed>" + entry("v1", body) + "</feed>", "v1")!!
        assertTrue(text.contains("•"), "маркер списка пропал: '$text'")
        assertTrue(text.contains("первое") && text.contains("второе"), "текст списка потерян: '$text'")
    }

    @Test
    fun splitsOnLineBreaksNotRuns() {
        val text = from("<feed>" + entry("v1", "&lt;p&gt;один&lt;br&gt;два&lt;/p&gt;") + "</feed>", "v1")!!
        assertEquals("один\nдва", text)
    }

    @Test
    fun decodesEntitiesBeforeStrippingTags() {
        // The decode has to come first: stripping tags out of "&lt;p&gt;" first
        // leaves the literal "&lt;p&gt;" on screen.
        //
        // ONE pass, deliberately. An owner writing "a & b" gets "&amp;" from
        // GitHub, and decoding it twice is how a real note ends up showing
        // "a & b" as "a &amp; b" - a second pass is not a safety net, it is a
        // bug that only shows up on the one note containing an ampersand.
        val text = from("<feed>" + entry("v1", "&lt;p&gt;a &amp; b&lt;/p&gt;") + "</feed>", "v1")!!
        assertEquals("a & b", text)
    }

    @Test
    fun keepsAnEntityThatWasMeantToBeSeen() {
        // The mirror case: "&amp;amp;" is the owner typing the characters
        // "&amp;" on purpose, and the reader must see exactly that.
        val text = from("<feed>" + entry("v1", "&lt;p&gt;&amp;amp;&lt;/p&gt;") + "</feed>", "v1")!!
        assertEquals("&amp;", text)
    }

    @Test
    fun anEmptyBodyIsNullNotBlankNoise() {
        // A release with no notes must read as "nothing to say", not as a box
        // holding a single blank line the user has to look at.
        assertNull(from("<feed>" + entry("v1", "&lt;p&gt;&lt;/p&gt;") + "</feed>", "v1"))
    }

    @Test
    fun theAuthorsOwnAngleBracketsSurvive() {
        // This project's CLI is written exactly this way. A blanket <[^>]*>
        // strip deleted the placeholder and left "добавьте --transport=".
        val text = from("<feed>" + entry("v1", "&lt;p&gt;добавьте --transport=&lt;name&gt;&lt;/p&gt;") + "</feed>", "v1")!!
        assertEquals("добавьте --transport=<name>", text)
    }

    @Test
    fun aNumericEntityIsDecodedAndABrokenOneIsLeftAlone() {
        // The feed carries the body already escaped, so an author who typed
        // "&amp;" arrives as "&amp;amp;" and must land on screen as "&amp;" -
        // one pass, not two. The out-of-range reference is left exactly as it
        // was: rewriting it would invent a character nobody typed.
        val body = "&lt;p&gt;а &amp;amp; б &amp;#110000;&lt;/p&gt;"
        val text = from("<feed>" + entry("v1", body) + "</feed>", "v1")!!
        assertEquals("а &amp; б &#110000;", text)
    }

    @Test
    fun theFeedsOwnMarkupDoesNotReachTheScreen() {
        // GitHub's footer is <a class="commit-link"><tt>v2.3.2...v2.3.3</tt></a>
        // and a Markdown table arrives as <table><thead><tr><th>. A short tag
        // list left all of it visible in the notes block.
        val body = "&lt;p&gt;Full Changelog: " +
            "&lt;a class=\"commit-link\" href=\"https://x/compare/v2.3.2...v2.3.3\"&gt;" +
            "&lt;tt&gt;v2.3.2...v2.3.3&lt;/tt&gt;&lt;/a&gt;&lt;/p&gt;"
        val text = from("<feed>" + entry("v1", body) + "</feed>", "v1")!!
        assertEquals("Full Changelog: v2.3.2...v2.3.3", text)
        assertFalse("<" in text, "сырая разметка на экране: $text")
    }

    @Test
    fun aMarkdownTableIsRenderedAsRows() {
        val body = "&lt;table&gt;&lt;thead&gt;&lt;tr&gt;&lt;th&gt;Файл&lt;/th&gt;&lt;th&gt;Что это&lt;/th&gt;&lt;/tr&gt;" +
            "&lt;/thead&gt;&lt;tbody&gt;&lt;tr&gt;&lt;td&gt;a.kt&lt;/td&gt;&lt;td&gt;разбор&lt;/td&gt;&lt;/tr&gt;&lt;/tbody&gt;&lt;/table&gt;"
        val text = from("<feed>" + entry("v1", body) + "</feed>", "v1")!!
        assertFalse("<" in text, "таблица пришла разметкой: $text")
        assertTrue("Файл" in text && "разбор" in text, "содержимое таблицы потеряно: $text")
    }

    @Test
    fun anAbsentTagIsNull() {
        assertNull(from("<feed>" + entry("v1", "текст") + "</feed>", "v9"))
    }

    @Test
    fun newestSkipsReleasesThatSayNothing() {
        // The newest tag often has an empty body; falling through is the point.
        val feed = "<feed>" + entry("v3", "") + entry("v2", "что поменялось") + entry("v1", "старше") + "</feed>"
        val found = ReleaseNotes.newest(feed, listOf("v3", "v2", "v1"))
        assertEquals("v2" to "что поменялось", found)
    }

    @Test
    fun handlesABodySpanningLines() {
        val body = "&lt;p&gt;первая строка\nвторая строка\n\nтретья&lt;/p&gt;"
        val text = from("<feed>" + entry("v1", body) + "</feed>", "v1")!!
        assertEquals("первая строка\nвторая строка\n\nтретья", text)
    }
}