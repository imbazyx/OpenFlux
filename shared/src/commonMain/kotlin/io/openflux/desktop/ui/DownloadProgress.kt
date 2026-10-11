package io.openflux.desktop.ui

/**
 * Formats a byte count the way a person reads it.
 *
 * Both languages the app ships in, so one function rather than a locale table:
 * the strings around it are Russian, and "18,8 МБ" is what the label next to
 * the other numbers on the settings screen already looks like.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "0 Б"
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    // One decimal, dropped when it is a trailing zero: the traffic counters next
    // to this text already read "25,7 КБ/с", so "19 МБ" beside them is the odd
    // one out.
//
// Computed by hand rather than with String.format, which follows the SYSTEM
// locale: on a Russian Windows it returns "2,0", the trailing-zero test below
// then never matches, and every size on screen grows a decimal it does not
// need. A comma here is the only one the screen is allowed to have, and the
// function that puts it there has to be the one that decides that.
    val tenths = kotlin.math.round(value * 10).toLong()
    val whole = tenths / 10
    val frac = tenths % 10
    val number = if (frac == 0L) "$whole" else "$whole,$frac"
    return "$number ${units[unit]}"
}

/**
 * The one line a download bar draws above itself.
 *
 * Two shapes on purpose. With a total it is a percentage, which is the only
 * thing that answers "how long left". Without one it is a running byte count,
 * because a bar sitting at zero while 14 МБ are arriving tells the user the
 * transfer is broken when it is working - the case the bar was added to fix.
 */
fun downloadProgressText(done: Long, total: Long): String = when {
    // Zero bytes with the size already known is the request being sent, not a
    // transfer at 0%. Drawing "0%" there is exactly the frozen-looking bar the
    // user was complaining about.
    done <= 0 -> "Подключение к GitHub…"
    total > 0 -> {
        val percent = (done.toDouble() * 100 / total).toInt().coerceIn(0, 100)
        "$percent%  ·  ${formatBytes(done)} из ${formatBytes(total)}"
    }
    else -> "Загружено ${formatBytes(done)}"
}

/** The fraction to draw, or null when there is no total to measure against. */
fun downloadProgressFraction(done: Long, total: Long): Float? =
    if (total > 0) (done.toDouble() * 100 / total).toFloat().coerceIn(0f, 100f) / 100f else null