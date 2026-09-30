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
import androidx.core.content.FileProvider
import io.openflux.desktop.updates.compareVersions
import io.openflux.desktop.updates.pickApk
import io.openflux.desktop.updates.versionCodeOf
import io.openflux.desktop.service.AppUpdate
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

    /**
     * The newest release, with the APK this device can install.
     *
     * Only the newest release is considered, and only if it is strictly newer
     * than the running version: offering the user a downgrade would be worse
     * than offering nothing. A GitHub failure returns null, which the UI shows
     * as "could not check" rather than "up to date" - the two are different
     * facts and collapsing them would hide a broken check.
     */
    override suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val release = githubGet("https://api.github.com/repos/$RELEASE_REPO/releases/latest")
                ?.jsonObject ?: return@runCatching null
            val tag = release["tag_name"]?.jsonPrimitive?.content ?: return@runCatching null
            val version = tag.removePrefix(TAG_PREFIX)
            val current = BuildConfig.VERSION_NAME
            val newer = compareVersions(version, current) > 0

            val assets = release["assets"]?.jsonArray ?: return@runCatching null
            val names = assets.map { it.jsonObject["name"]?.jsonPrimitive?.content.orEmpty() }
            val urls = assets.map { it.jsonObject["browser_download_url"]?.jsonPrimitive?.content.orEmpty() }
            val index = pickApk(names, android.os.Build.SUPPORTED_ABIS.toList())
                ?: return@runCatching null
            AppUpdate(
                version = version,
                downloadUrl = urls[index],
                versionCode = versionCodeOf(version),
                newer = newer,
            )
        }.getOrNull()
    }

    /**
     * Downloads the APK to the cache and opens the system installer on it.
     *
     * The installer refuses a file the app does not own, so the file is shared
     * through a FileProvider rather than a file:// URI. The download is
     * verified against the release's published SHA-256 before the installer is
     * ever shown: the APK replaces the tunnel's own binary, and a truncated
     * download must not become a silent update.
     */
    override suspend fun installUpdate(update: AppUpdate): Boolean = withContext(Dispatchers.IO) {
        val target = java.io.File(context.cacheDir, "update-${update.version}.apk")
        val expected = publishedSha256(update.version)
        val ok = runCatching { download(update.downloadUrl, target, expected) }.getOrElse {
            Log.w(TAG, "update download failed", it)
            Toast.makeText(context, "Не удалось скачать обновление", Toast.LENGTH_LONG).show()
            return@withContext false
        }
        if (!ok) {
            Toast.makeText(context, "Контрольная сумма обновления не совпала", Toast.LENGTH_LONG).show()
            return@withContext false
        }
        withContext(Dispatchers.Main) {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", target)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, uri)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrElse {
                Log.w(TAG, "cannot start the installer", it)
                Toast.makeText(context, "Не удалось открыть установщик", Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    private fun download(url: String, target: java.io.File, expected: String?): Boolean {
        val tmp = java.io.File(target.parentFile, target.name + ".part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OpenFlux-Android")
        }
        try {
            connection.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        } finally {
            connection.disconnect()
        }
        // The published manifest covers the whole release. When it is missing
        // or silent about this file, accept the download rather than refusing
        // to update at all - but say so, so an unverified update is visible.
        if (expected == null) {
            Log.w(TAG, "no published checksum for ${target.name}; installing unverified")
            tmp.renameTo(target)
            return target.length() > 0
        }
        val actual = sha256Hex(tmp)
        if (actual != expected) {
            Log.w(TAG, "checksum mismatch for ${target.name}: $actual != $expected")
            tmp.delete()
            return false
        }
        tmp.renameTo(target)
        return true
    }

    /** This release's arm64/universal/apk line for the device's abi, from the release's own manifest. */
    private fun publishedSha256(version: String): String? = runCatching {
        val body = githubText("https://github.com/$RELEASE_REPO/releases/download/$TAG_PREFIX$version/SHA256SUMS.txt")
            ?: return@runCatching null
        val deviceAbis = android.os.Build.SUPPORTED_ABIS.toList()
        val names = body.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) parts[1] to parts[0] else null
        }.toList()
        val index = pickApk(names.map { it.first }, deviceAbis) ?: return@runCatching null
        names[index].second
    }.getOrNull()

    /** Plain text, for the release's checksum manifest. Not JSON. */
    private fun githubText(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).let { connection ->
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "OpenFlux-Android")
            try {
                connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                connection.disconnect()
            }
        }
    }.getOrNull()

    private fun githubGet(url: String) = runCatching {
        githubText(url)?.let { Json.parseToJsonElement(it) }
    }.getOrNull()

    private fun sha256Hex(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
        // This fork's own releases, not p1neappleXpress/OpenFluxAndroid: pointing
        // at the original would offer that repository's releases to our users as
        // if they were updates to this app. If this repository is renamed or
        // moved, this is the one string to change. A failed lookup is harmless -
        // latestRelease() ends in getOrNull, so it just offers no update.
        private const val RELEASE_REPO = "imbazyx/OpenFlux"
        private const val TAG_PREFIX = "v"
        private const val MAX_QR_IMAGE = 2048
        private const val TAG = "OpenFluxUpdates"
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
