package io.openflux.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadProgressTest {

    @Test
    fun bytesBelowAKilobyteStayInBytes() {
        assertEquals("512 Б", formatBytes(512))
        assertEquals("0 Б", formatBytes(0))
    }

    @Test
    fun formatsMegabytesTheWayTheRestOfTheScreenDoes() {
        // The traffic counters already read "25,7 КБ/с" with a comma; a dot
        // here would be the only place on screen using the other convention,
        // and String.format on a Russian Windows produces exactly that dot.
        assertEquals("23,8 МБ", formatBytes(25_000_000L))
    }

    @Test
    fun oneDecimalOnlyWhileItSaysSomething() {
        // "2,0 МБ" is noise on top of "2 МБ"; below ten the tenth is real.
        assertEquals("2 МБ", formatBytes(2_097_152L))
        assertEquals("9,5 МБ", formatBytes(9_961_472L))
        assertEquals("117,9 МБ", formatBytes(123_586_000L))
    }

    @Test
    fun percentageComesFromTheTotalNotTheClock() {
        assertEquals("50%  ·  10 МБ из 20 МБ", downloadProgressText(10_485_760L, 20_971_520L))
    }

    @Test
    fun noTotalMeansBytesAndNotAPercentage() {
        // The whole point: a frozen 0% while data arrives is the failure this
        // replaces. It must show the running count instead.
        assertEquals("Загружено 14 МБ", downloadProgressText(14_680_064L, 0L))
    }

    @Test
    fun beforeTheFirstByteItIsTheServerHandshakeNotZeroPercent() {
        assertEquals("Подключение к GitHub…", downloadProgressText(0L, 20_971_520L))
        assertEquals("Подключение к GitHub…", downloadProgressText(0L, 0L))
    }

    @Test
    fun fractionIsAbsentWithoutATotal() {
        // An indeterminate bar has to be told so by a null, not by 0f: 0f and
        // "unknown" draw differently and only one of them is honest here.
        assertNull(downloadProgressFraction(512L, 0L))
        assertEquals(0.5f, downloadProgressFraction(50L, 100L))
    }

    @Test
    fun overshootCannotDrawABarPastItsEnd() {
        // A server that sends more than it promised must not produce 100.4%.
        assertEquals(1f, downloadProgressFraction(120L, 100L))
        assertEquals(0f, downloadProgressFraction(0L, 100L))
    }

    @Test
    fun aNegativeCountDoesNotProduceANegativeBar() {
        // Defensive, not hypothetical: -1 is what several stream APIs report
        // for "size unknown" when they answer a different question than ours.
        // Asserted on the value, not on "not negative": `?: 0f` in the old
        // version made this pass for an implementation that returned null -
        // and for one that returned -1f.
        assertEquals(0f, downloadProgressFraction(-1L, 100L))
        assertEquals("0 Б", formatBytes(-1))
    }
}