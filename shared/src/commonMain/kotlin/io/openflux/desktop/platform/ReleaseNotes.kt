package io.openflux.desktop.platform

/**
 * What a release said about itself, pulled out of the GitHub releases feed.
 *
 * One implementation for both clients, for the same reason the download
 * progress is one: the two front ends were given separate parsers for the same
 * feed and had already drifted, and a user comparing the Android screen with
 * the desktop one would have seen two different answers to one question.
 */
object ReleaseNotes {

    /**
     * The release body for [tag], cleaned to plain text, or null when the feed
     * carries none for that tag.
     *
     * The feed stores the body as escaped HTML inside `<content>`, so it arrives
     * as `&lt;p&gt;что изменилось&lt;/p&gt;`. Left as it is, the screen would
     * show markup; stripped without decoding first, the user would read
     * "&lt;p&gt;" literally.
     */
    fun from(feed: String, tag: String): String? {
        val entry = entries(feed).firstOrNull { it.tag == tag } ?: return null
        return cleanup(entry.content).takeIf { it.isNotBlank() }
    }

    /** The newest app tag in [feed] that has a non-empty body, with that body. */
    fun newest(feed: String, tags: List<String>): Pair<String, String>? {
        for (tag in tags) {
            val text = from(feed, tag) ?: continue
            if (text.isNotBlank()) return tag to text
        }
        return null
    }

    private data class Entry(val tag: String, val content: String)

    private fun entries(feed: String): List<Entry> =
        // A release with no body still has an <entry>, just an empty one, so
        // the split must find elements rather than assume a shape.
        Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(feed)
            .mapNotNull { m ->
                val block = m.groupValues[1]
                val tag = Regex("""/releases/tag/([^"/]+)""").find(block)?.groupValues?.get(1)
                    ?.trim()
                    ?: return@mapNotNull null
                Entry(tag, contentOf(block))
            }
            .toList()

    /**
     * The `<content>` body, HTML entities decoded.
     *
     * Only the five entities GitHub actually emits are handled. A general
     * unescape would pull in a parser for the sake of numeric references that
     * do not appear in a hand-written release note, and a hand-written note
     * that contains "&nbsp;" is not a thing anyone has ever needed.
     */
    private fun contentOf(block: String): String =
        Regex("""<content[^>]*>(.*?)</content>""", RegexOption.DOT_MATCHES_ALL)
            .find(block)?.groupValues?.get(1) ?: ""

    private const val BULLET = "\u0002"

    /**
     * The tags a release note is allowed to contain. A name outside this list
     * is treated as the author's text, never as markup.
     *
     * Taken from what the releases feed really carries: GitHub's own footer is
     * `<a class="commit-link"><tt>v2.3.2...v2.3.3</tt></a>`, and a note written
     * as a Markdown table arrives as `<table><thead><tr><th>`. A short list
     * left the user's screen full of raw angle brackets, which is worse than the
     * case this replaced.
     */
    private const val TAG_NAMES =
        "p|div|li|ul|ol|dl|dt|dd|h[1-6]|tr|td|th|thead|tbody|tfoot|table|caption|" +
            "strong|b|em|i|u|s|del|ins|sub|sup|code|pre|kbd|samp|var|" +
            "a|span|br|hr|img|tt|blockquote|figure|figcaption|details|summary"

    /** Unescape first, strip markup second, then tidy the whitespace. */
    fun cleanup(raw: String): String = collapse(stripTags(unescape(raw)))

    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&laquo;", "«")
        .replace("&raquo;", "»")
        .replace("&mdash;", "—")
        .replace("&hellip;", "…")
        // Numeric references: the feed carries &#8212; for an em dash as often as
        // it carries the name, and a named one that is not in the list above
        // reaches the screen as the literal "&#8212;".
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { m -> codepoint(m.groupValues[1], 16, m.value) }
        .replace(Regex("""&#(\d+);""")) { m -> codepoint(m.groupValues[1], 10, m.value) }
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        // Ampersand LAST, and only once, because it is the one that can be an
        // escape of an escape: the feed carries "&amp;amp;" for a note that
        // literally reads "a &amp; b", and a single-pass replace left the "amp;"
        // sitting on screen. Replacing it after the other four also cannot
        // collide with them, while a bare "&" mid-note still survives intact.
        .replace("&amp;", "&")

    /**
     * A malformed or out-of-range reference is left exactly as written: dropping
     * the note, or rendering `&#x110000;` as the decimal `&#110000;`, would both
     * change what the author wrote.
     */
    private fun codepoint(digits: String, radix: Int, fallback: String): String =
        digits.toIntOrNull(radix)
            ?.takeIf { it in 1..0x10FFFF }
            ?.let { runCatching { String(Character.toChars(it)) }.getOrNull() }
            ?: fallback

    /**
     * Turns markup into the text a person reads.
     *
     * List items and headings are turned into line breaks rather than deleted,
     * because a release note is mostly a list and dropping the bullets would
     * leave one run-on sentence.
     *
     * Only KNOWN tag names are removed. `<name>` in a release note is text -
     * this project's own CLI syntax writes `--transport=<name>` - and a blanket
     * `<[^>]*>` deleted everything inside it, so a note could lose the one word
     * the reader needed.
     */
    private fun stripTags(s: String): String = s
        .replace(Regex("""(?i)<\s*br\s*/?\s*>"""), "\n")
        .replace(Regex("""(?i)<\s*li[^>]*>"""), BULLET)
        .replace(Regex("""(?i)<\s*/\s*($TAG_NAMES)\s*>"""), "\n")
        .replace(Regex("""(?i)<\s*($TAG_NAMES)\b[^>]*>"""), "")
        .replace(BULLET, "• ")

    private fun collapse(s: String): String = s
        .replace("\r\n", "\n")
        .split('\n')
        .joinToString("\n") { it.trim() }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}