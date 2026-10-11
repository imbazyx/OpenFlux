package io.openflux.desktop.model

/**
 * Whether [secret] has the shape of a key this project hands out.
 *
 * Two shapes are real, and refusing either one breaks a working setup:
 *  - 64 lowercase hex characters - what the core's provision.NewKey() produces
 *    (provision.go:447), what node-install.sh validates (deploy/node-install.sh:213)
 *    and what both clients generate in "Сгенерировать безопасный ключ";
 *  - 44 characters of standard base64 ending in "=" - the same 32 bytes in the
 *    other encoding, and the form the keys in circulation were created in.
 *
 * A regex, not a decoder, on purpose: java.util.Base64 throws on a stray byte
 * while android.util.Base64.DEFAULT silently skips one, so two decoders made
 * the phone and the PC disagree about the same paste. Shape is all that can be
 * checked here anyway - the core takes any secret of 16 characters or more and
 * hashes it (transport/encrypted.go:77), so a key that is intact but wrong can
 * only be caught by the node refusing it.
 */
private val HEX_KEY = Regex("^[0-9a-f]{64}$")
private val BASE64_KEY = Regex("^[A-Za-z0-9+/]{43}=$")

/** Whitespace around the paste is forgiven; it is never part of the key. */
fun isSecretKey(secret: String): Boolean {
    val s = secret.trim()
    return HEX_KEY.matches(s) || BASE64_KEY.matches(s)
}

/** One wording for every screen that talks about the key field. */
const val SECRET_KEY_SHAPE = "64 шестнадцатеричных символа или 44 символа Base64, оканчивающиеся на «=»"