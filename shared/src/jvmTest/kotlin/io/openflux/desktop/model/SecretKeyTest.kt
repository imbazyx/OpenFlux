package io.openflux.desktop.model

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretKeyTest {

    /** What provision.NewKey() and "Сгенерировать безопасный ключ" both produce. */
    private fun hexKey(): String = "0123456789abcdef".repeat(4)

    /** The same 32 bytes in the other encoding - the keys in circulation. */
    private fun base64Key(): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun acceptsBothShapesTheProjectHandsOut() {
        assertTrue(isSecretKey(hexKey()), "64 hex-символа должен пройти")
        assertTrue(isSecretKey(base64Key()), "44 символа Base64 должен пройти")
        assertTrue(isSecretKey("[REDACTED]"), "ключ владельца")
        assertTrue(isSecretKey("[REDACTED]"), "ключ с «/»")
    }

    @Test
    fun acceptsAKeyPastedWithWhitespaceAroundIt() {
        // Ключ копируют из настроек в мессенджер и обратно — пробелы по краям
        // должны прощаться, а не ругаться на пользователя.
        assertTrue(isSecretKey("  ${hexKey()}\n"), "пробелы по краям не должны ломать ключ")
    }

    @Test
    fun rejectsGarbageAtKeyLength() {
        assertFalse(isSecretKey("g".repeat(64)), "не hex")
        assertFalse(isSecretKey("x".repeat(43) + "!"), "не base64")
        assertFalse(isSecretKey("x".repeat(42) + "=="), "неверное дополнение")
    }

    @Test
    fun rejectsADamagedKey() {
        // A key whose base64 actually contains the two symbols that differ
        // between alphabets; replacing them makes base64url, which the node -
        // which hashes the bytes as given - cannot match.
        val base64 = "[REDACTED]"
        assertTrue(isSecretKey(base64), "исходный ключ проходит")
        assertFalse(isSecretKey(base64.replace('/', '_')), "base64url вместо base64")
        assertFalse(isSecretKey(hexKey().uppercase()), "заглавные hex-символы")
        assertFalse(isSecretKey(base64.dropLast(1)), "обрезанный ключ")
        assertFalse(isSecretKey(base64.drop(1)), "ключ без первого символа")
        assertFalse(isSecretKey(hexKey().dropLast(1)), "нечётное число символов")
    }

    @Test
    fun rejectsAKeyWithASpaceInside() {
        assertFalse(isSecretKey(hexKey().drop(1).replaceRange(10, 11, " ")), "пробел внутри")
    }
}