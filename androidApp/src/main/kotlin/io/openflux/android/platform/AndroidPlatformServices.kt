package io.openflux.android.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.widget.Toast
import io.openflux.android.core.AppSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.openflux.android.ActivityBridge
import io.openflux.android.BuildConfig
import io.openflux.desktop.service.PlatformKind
import io.openflux.desktop.service.PlatformServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

class AndroidPlatformServices(
    private val context: Context,
    private val bridge: ActivityBridge,
) : PlatformServices {
    private val random = SecureRandom()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    init {
        instance = this
    }

    override val kind = PlatformKind.Android
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val coreVersion: String = BuildConfig.CORE_VERSION
    override val clientRepo: String = RELEASE_REPO
    override val systemProxySupported = false
    /** The VPN: the whole phone through the node. */
    override val fullTunnelSupported = true
    override val elevated = true

    override fun restartElevated() = false

    override fun clipboardText(): String? =
        runCatching { clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()

    override fun setClipboardText(text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenFlux", text))
    }

    override val clipboardImageSupported = false

    override fun qrFromClipboardImage(): String? = null

    override fun qrFromFile(path: String): String? = runCatching { loadBitmap(Uri.parse(path))?.let(::decodeQr) }.getOrNull()

    override suspend fun pickFile(title: String, extensions: List<String>): String? {
        val images = extensions.isNotEmpty() && extensions.all { it in IMAGE_EXTENSIONS }
        return bridge.pickDocument(if (images) arrayOf("image/*") else arrayOf("*/*"))?.toString()
    }

    override val cameraScanSupported: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    override suspend fun scanQr(): String? = bridge.scanQr()

    override fun readTextFile(path: String, maxBytes: Int): String? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
            val bytes = input.readNBytesCompat(maxBytes + 1)
            if (bytes.size > maxBytes) null else bytes.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    override fun qrMatrix(text: String): List<BooleanArray> {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
    }

    override fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override val perAppSelectionSupported = true

    private val appSelection = MutableStateFlow(0)

    override val appSelectionRev: StateFlow<Int> get() = appSelection

    /** Called after every write to the stored rule; see [appSelectionRev]. */
    fun appSelectionChanged() {
        appSelection.value++
    }

    override fun perAppSummary(): String {
        val context = context.applicationContext
        if (!AppSelection.onlySelected(context)) {
            return "Правило: через ноду идёт весь трафик, кроме самого OpenFlux."
        }
        // Report the rule as it will be applied, not as it was typed: packages
        // that are no longer installed are dropped at connect time.
        val live = AppSelection.selected(context).count { pkg ->
            pkg != context.packageName &&
                runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }
        return if (live == 0) {
            "Правило: выбранных приложений больше нет, при подключении пойдёт весь трафик."
        } else {
            "Правило: через ноду идут приложения, выбранные в списке" +
                " (${pluralApps(live)}); остальные — напрямую."
        }
    }

    override fun openAppSelector(): Boolean = runCatching {
        context.startActivity(
            Intent(context, AppSelectionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        true
    }.getOrElse { error ->
        // The shared settings screen has no channel to show an error, and the
        // picker is reachable only from that one button: without a toast here
        // the whole per-app feature would be unreachable and completely silent.
        Log.w("OpenFluxPlatform", "cannot open the app picker", error)
        Toast.makeText(
            context,
            "Не удалось открыть список приложений",
            Toast.LENGTH_LONG,
        ).show()
        false
    }

    override fun newSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun now(): Long = System.currentTimeMillis()

    override suspend fun latestRelease(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://api.github.com/repos/$RELEASE_REPO/releases?per_page=20").openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "OpenFlux-Android")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            Json.parseToJsonElement(body).jsonArray
                .map { it.jsonObject["tag_name"]?.jsonPrimitive?.content.orEmpty() }
                .firstOrNull { it.startsWith(TAG_PREFIX) }
                ?.removePrefix(TAG_PREFIX)
        }.getOrNull()
    }

    /** The picked image, scaled down so a camera photo does not exhaust memory. */
    private fun loadBitmap(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_QR_IMAGE || bounds.outHeight / sample > MAX_QR_IMAGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun decodeQr(image: Bitmap): String? {
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        return try {
            MultiFormatReader().decode(
                bitmap,
                mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true),
            ).text
        } catch (_: NotFoundException) {
            null
        }
    }

    // Not private: AppSelection.save() ticks the revision through [instance].
    companion object {
        // ПОДГОТОВКА К ПУБЛИКАЦИИ: подставьте свой аккаунт GitHub перед
        // публикацией. Пока стоит заглушка — latestRelease() вернёт null
        // (там getOrNull), и приложение просто не предложит обновление.
        // Оставлять здесь p1neappleXpress нельзя: форк показывал бы чужие
        // релизы как свои обновления.
        private const val RELEASE_REPO = "REPLACE_WITH_GITHUB_ACCOUNT/OpenFluxAndroid"
        private const val TAG_PREFIX = "v"
        private const val MAX_QR_IMAGE = 2048
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp")

        /**
         * The live instance, so [io.openflux.android.core.AppSelection.save] can
         * tick the revision without the shared layer having to know about it.
         * Null until the app builds its container.
         */
        @Volatile
        var instance: AndroidPlatformServices? = null
            internal set
    }
}

/**
 * Russian count form: 1 приложение, 2-4 приложения, 5+ приложений. A bare
 * "$n приложений" reads as broken in the one language this app speaks.
 */
internal fun pluralApps(n: Int): String {
    val mod100 = n % 100
    return if (mod100 in 11..14) "$n приложений"
    else when (n % 10) {
        1 -> "$n приложение"
        2, 3, 4 -> "$n приложения"
        else -> "$n приложений"
    }
}

/** InputStream.readNBytes arrived in API 33. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
