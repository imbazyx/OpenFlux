package io.openflux.desktop.data

import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareLinkCodec
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * `openflux://v1/` + base64url(raw DEFLATE(JSON)), byte-compatible with the
 * core's share package (Go's compress/flate writes raw DEFLATE, no zlib
 * header, hence `nowrap`).
 */
class JvmShareLinkCodec : ShareLinkCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    override fun encode(config: ShareConfig): String {
        config.validate()
        val raw = json.encodeToString(ShareConfig.serializer(), config).toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return ShareLinkCodec.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    }

    override fun decode(link: String): ShareConfig {
        val trimmed = link.trim()
        require(trimmed.startsWith(ShareLinkCodec.PREFIX)) {
            if (trimmed.startsWith("openflux://")) "Неподдерживаемая версия ссылки, обновите OpenFlux" else "Это не ссылка openflux://"
        }
        val packed = try {
            Base64.getUrlDecoder().decode(trimmed.removePrefix(ShareLinkCodec.PREFIX))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Ссылка повреждена")
        }
        val inflater = Inflater(true)
        inflater.setInput(packed)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                require(out.size() <= MAX_PAYLOAD) { "Ссылка слишком большая" }
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw IllegalArgumentException("Ссылка повреждена")
        } finally {
            inflater.end()
        }
        val config = try {
            // `out.toByteArray()` and not `out.toString(Charsets.UTF_8)`: that
            // overload is Java 10 and only landed in Android API 33. On a phone
            // (Android 11 here) it is a NoSuchMethodError at the moment a link is
            // pasted - "No virtual method toString(Ljava/nio/charset/Charset;)" -
            // which reads like a broken link and is not one. The desktop JVM has
            // the method, so only the phone ever failed.
            json.decodeFromString(ShareConfig.serializer(), String(out.toByteArray(), Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalArgumentException("Ссылка повреждена")
        }
        config.validate()
        return config
    }

    private companion object {
        /** Same bound as the core's decoder. */
        const val MAX_PAYLOAD = 16 shl 10
    }
}
