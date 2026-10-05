package io.openflux.android.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import io.openflux.desktop.updates.compareVersions
import io.openflux.desktop.updates.pickApk
import io.openflux.desktop.updates.versionCodeOf
import io.openflux.desktop.service.AppUpdate
import io.openflux.desktop.service.UpdateCheck
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
        val clip = ClipData.newPlainText("OpenFlux", text)
        // The share link carries the node's encryption key. On Android 8.0-9.0
        // (still in range: minSdk 26) any app holding focus, and the IME, can
        // read the clipboard, so the platform needs to be told this is secret.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)
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
        releaseFeed()?.let { newestTag(it) }?.removePrefix(TAG_PREFIX)
    }

    /**
     * The newest release, with the APK this device can install.
     *
     * Only the newest release is considered, and only if it is strictly newer
     * than the running version: offering the user a downgrade would be worse
     * than offering nothing.
     *
     * This reads the releases Atom feed rather than the REST API, for the same
     * reason the desktop client does: unauthenticated API calls are limited per
     * address, and the 403 that comes back from an exhausted quota arrived here
     * as a null indistinguishable from "nothing new". A user on a phone is on a
     * mobile carrier address far more often than anyone is behind a fixed line,
     * so this is where the limit is actually reached.
     */
    override suspend fun checkForUpdateDetailed(): UpdateCheck = withContext(Dispatchers.IO) {
        val feed = releaseFeed()
            ?: return@withContext UpdateCheck.Failed("не удалось прочитать список выпусков с GitHub")
        val tag = newestTag(feed)
            ?: return@withContext UpdateCheck.Failed("в списке выпусков не найдено ни одного тега")

        val version = tag.removePrefix(TAG_PREFIX)

        // Every ABI this device can run, best first, then the universal build.
        // The universal APK is the fallback the old check had through pickApk:
        // a release that published only it, or only a narrower ABI than this
        // device prefers, must still be installable - otherwise a phone whose
        // first supported ABI has no split simply can never update.
        val candidates = android.os.Build.SUPPORTED_ABIS.filter { it in KNOWN_ABIS } + "universal"
        val found = candidates.firstNotNullOfOrNull { abi ->
            val name = "OpenFluxAndroid-$version-androidApp-$abi-release.apk"
            val url = "https://github.com/$RELEASE_REPO/releases/download/$tag/$name"
            if (exists(url)) url else null
        }
            ?: return@withContext UpdateCheck.Failed("выпуск $tag есть, но APK для этого устройства не отдаётся")

        val update = AppUpdate(
            version = version,
            downloadUrl = found,
            versionCode = versionCodeOf(version),
            newer = compareVersions(version, BuildConfig.VERSION_NAME) > 0,
        )
        if (update.newer) UpdateCheck.Available(update) else UpdateCheck.UpToDate(version)
    }

    override suspend fun checkForUpdate(): AppUpdate? =
        when (val result = checkForUpdateDetailed()) {
            is UpdateCheck.Available -> result.update
            is UpdateCheck.UpToDate -> AppUpdate(
                version = result.latestVersion,
                downloadUrl = "",
                versionCode = versionCodeOf(result.latestVersion),
                newer = false,
            )
            is UpdateCheck.Failed -> null
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
    // Every Toast goes through the Main dispatcher, including the two
    // failure ones in installUpdate and the unverified-update notice in
    // download: both run on Dispatchers.IO, and Toast binds its Handler to the
    // calling thread, so raising it there is at best posting to the wrong
    // looper and at worst throwing on an API 26-30 device - which would turn
    // "download failed" into a crash. A member rather than a local, because
    // download() is a separate method and could not see a local one.
    private fun tell(message: String) = CoroutineScope(Dispatchers.Main).launch {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    override suspend fun installUpdate(update: AppUpdate): Boolean = withContext(Dispatchers.IO) {
        val target = java.io.File(context.cacheDir, "update-${update.version}.apk")
        val expected = publishedSha256(update.version)
        val ok = runCatching { download(update.downloadUrl, target, expected) }.getOrElse {
            Log.w(TAG, "update download failed", it)
            tell("Не удалось скачать обновление")
            return@withContext false
        }
        if (!ok) {
            tell("Контрольная сумма обновления не совпала")
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
        if (expected != null) {
            val actual = sha256Hex(tmp)
            if (actual != expected) {
                Log.w(TAG, "checksum mismatch for ${target.name}: $actual != $expected")
                tmp.delete()
                return false
            }
        } else {
            // Also to the user, not only to logcat. The desktop twin says this
            // through BrowserLog, which lands on its Logs screen; a phone user
            // with no adb attached was being told nothing, which is the
            // opposite of what the comment above this branch promises.
            Log.w(TAG, "no published checksum for ${target.name}; installing unverified")
            tell("Обновление ставится без проверки подписи")
        }
        // renameTo's result used to be discarded here and true returned anyway.
        // The target is named per version and never deleted, so a rename that
        // failed - because PackageInstaller still held the previous APK, or the
        // cache was restored underneath us - reported success and the installer
        // was handed the OLD file: a silent rollback presented as a fresh
        // update, on the one path the owner requires not to break.
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            Log.w(TAG, "cannot install the downloaded APK at ${target.name}")
            return false
        }
        // Kept from the desktop twin, which never lost it. On the unverified
        // path there is no checksum to catch a truncated download, so a server
        // answering 200 with an empty body reached PackageInstaller as a 0-byte
        // file and failed there with an opaque "not a valid archive" instead of
        // the app's own message.
        if (target.length() == 0L) {
            Log.w(TAG, "the downloaded APK at ${target.name} is empty; not installing")
            target.delete()
            return false
        }
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

    /** The releases feed, which GitHub serves without an API token and without a per-address quota. */
    private fun releaseFeed(): String? = githubText("https://github.com/$RELEASE_REPO/releases.atom")

    /**
     * The newest release tag, e.g. "v2.2.0".
     *
     * From the entry's link, not its id: the Atom id is
     * `tag:github.com,2008:Repository/<id>/v2.2.0` and carries no releases path,
     * so a parser looking for one finds nothing and reports "no update".
     */
    internal fun newestTag(feed: String): String? =
        Regex("""<link[^>]*href="[^"]*/releases/tag/([^"/]+)"""")
            .find(feed)?.groupValues?.get(1)?.trim()

    /** True when the URL resolves, so a download is not offered before it exists. */
    private fun exists(url: String): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.requestMethod = "HEAD"
        connection.setRequestProperty("User-Agent", "OpenFlux-Android")
        val code = connection.responseCode
        connection.disconnect()
        code in 200..399
    }.getOrDefault(false)

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
        // This repository's own releases: pointing anywhere else would offer
        // that repository's releases to our users as if they were updates to
        // this app. If this repository is renamed or
        // moved, this is the one string to change.
        private const val RELEASE_REPO = "imbazyx/OpenFlux"
        private const val TAG_PREFIX = "v"
        private const val MAX_QR_IMAGE = 2048
        private const val TAG = "OpenFluxUpdates"
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif", "webp")

        /**
         * ABIs the build publishes a split for, checked against the assets of
         * v2.2.0. "universal" is appended by the caller, not listed here, so the
         * device's own preference order decides between the real splits.
         */
        private val KNOWN_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

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
