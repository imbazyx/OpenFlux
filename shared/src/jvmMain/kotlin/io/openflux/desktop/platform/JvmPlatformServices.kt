package io.openflux.desktop.platform

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
import io.openflux.desktop.service.AppUpdate
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.updates.compareVersions
import io.openflux.desktop.updates.versionCodeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import javax.imageio.ImageIO

class JvmPlatformServices(
    override val appVersion: String,
    private val coreVersionProvider: () -> String,
) : PlatformServices {
    private val os = System.getProperty("os.name").lowercase()
    private val random = SecureRandom()

    override val coreVersion: String get() = coreVersionProvider()
    override val clientRepo: String get() = RELEASE_REPO
    override val systemProxySupported: Boolean = os.contains("win")
    override val fullTunnelSupported: Boolean = os.contains("win")
    override val elevated: Boolean get() = WindowsElevation.elevated

    override fun restartElevated(): Boolean {
        if (!WindowsElevation.restartElevated(RELAUNCHED_ARG)) return false
        // createAppContainer registered the shutdown hook, so exiting here
        // stops the core and puts the system proxy back.
        kotlin.system.exitProcess(0)
    }

    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun clipboardText(): String? = runCatching {
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as String else null
    }.getOrNull()

    override fun setClipboardText(text: String) {
        clipboard.setContents(StringSelection(text), null)
    }

    override fun qrFromClipboardImage(): String? = runCatching {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        decodeQr(toBuffered(clipboard.getData(DataFlavor.imageFlavor) as Image))
    }.getOrNull()

    override fun qrFromFile(path: String): String? = runCatching {
        ImageIO.read(File(path))?.let(::decodeQr)
    }.getOrNull()

    // The AWT dialog is modal on the UI thread, as when it was called from a click.
    override suspend fun pickFile(title: String, extensions: List<String>): String? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (extensions.isNotEmpty()) {
            dialog.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(".$it") } }
            if (os.contains("win")) dialog.file = extensions.joinToString(";") { "*.$it" }
        }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it).absolutePath }
    }

    override fun readTextFile(path: String, maxBytes: Int): String? = runCatching {
        val file = File(path)
        if (!file.isFile || file.length() > maxBytes) return null
        file.readText()
    }.getOrNull()

    override fun qrMatrix(text: String): List<BooleanArray> {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
    }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun newSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun now(): Long = System.currentTimeMillis()

    /**
     * The releases Atom feed, which GitHub serves without an API token.
     *
     * The REST API was the obvious choice and it fails in the one situation
     * where a user is most likely to ask: unauthenticated requests are limited
     * per IP address, so everyone behind one connection - a office, a mobile
     * carrier, a VPN, which is a fair use for this program - exhausts a shared
     * budget together. The answer was a 403 that looked exactly like "no
     * update". The feed has no such limit, and for "what is the newest tag" it
     * carries the same fact.
     */
    private suspend fun releaseFeed(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL).build()
            val request = HttpRequest.newBuilder(URI("https://github.com/$RELEASE_REPO/releases.atom"))
                .header("User-Agent", "OpenFlux-Desktop").timeout(Duration.ofSeconds(15)).build()
            http.send(request, HttpResponse.BodyHandlers.ofString())
                .takeIf { it.statusCode() == 200 }?.body()
        }.getOrNull()
    }

    /**
     * The newest client release tag, e.g. "v2.1.0".
     *
     * Read from the entry's <link href>, not its <id>: the id is
     * `tag:github.com,2008:Repository/<id>/v2.1.0`, which has no releases path
     * in it at all and changes shape with the repository. The link is the plain
     * `.../releases/tag/v2.1.0` and is what the download URL is built from, so
     * the two cannot disagree.
     */
    internal fun newestTag(feed: String): String? =
        Regex("""<link[^>]*href="[^"]*/releases/tag/([^"/]+)"""")
            .find(feed)?.groupValues?.get(1)?.trim()

    /** True when the URL resolves - so a renamed asset cannot become a download. */
    private fun exists(url: String): Boolean = runCatching {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
        val head = HttpRequest.newBuilder(URI(url))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .header("User-Agent", "OpenFlux-Desktop").timeout(Duration.ofSeconds(15)).build()
        http.send(head, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    override suspend fun latestRelease(): String? =
        releaseFeed()?.let { newestTag(it) }?.removePrefix(DESKTOP_TAG_PREFIX)

    private fun decodeQr(image: BufferedImage): String? {
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
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

    private fun toBuffered(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return buffered
    }

    /**
     * The newest release that carries a Windows installer.
     *
     * This used to be unimplemented on the desktop, so the button reported
     * "could not check" on every press while the README promised the Windows
     * client would do exactly this. A failure still returns null - the UI shows
     * "could not check" rather than "up to date", because those are different
     * facts.
     */
    override suspend fun checkForUpdate(): AppUpdate? {
        val feed = releaseFeed() ?: return null
        val tag = newestTag(feed) ?: return null
        val version = tag.removePrefix(DESKTOP_TAG_PREFIX)

        // The installer is named after the version by the same rule the build
        // uses. Asking whether the file is actually there means a change to that
        // rule leaves the app quietly reporting "no update" rather than handing
        // the user a download link that 404s halfway through an install.
        val name = "OpenFlux-$version$WINDOWS_INSTALLER_SUFFIX"
        val url = "https://github.com/$RELEASE_REPO/releases/download/$tag/$name"
        if (!exists(url)) return null

        return AppUpdate(
            version = version,
            downloadUrl = url,
            versionCode = versionCodeOf(version),
            newer = compareVersions(version, appVersion) > 0,
        )
    }

    /**
     * Downloads the MSI and hands it to Windows Installer.
     *
     * Verified against the release's own SHA256SUMS.txt before msiexec is
     * started, for the reason Android verifies its APK: this file replaces the
     * running program, and a truncated download must not become a silent
     * update.
     *
     * msiexec /i runs the install straight away, and Windows Installer will
     * refuse to replace files the running app holds open. So the install is
     * started and the caller closes the app; that is why this returns true for
     * "handed over", not "finished".
     */
    override suspend fun installUpdate(update: AppUpdate): Boolean = withContext(Dispatchers.IO) {
        val target = File(System.getProperty("java.io.tmpdir"), "OpenFlux-${update.version}-setup.msi")
        val downloaded = download(update.downloadUrl, target, publishedSha256(update.version, target.name))
        if (!downloaded) return@withContext false

        ProcessBuilder("msiexec", "/i", target.absolutePath)
            .redirectErrorStream(true)
            .start()
        true
    }

    /**
     * Fetches [url] to [target], checking it against [expected] when the
     * release publishes a checksum for this exact file. A missing manifest
     * entry is a warning, not a refusal: refusing would mean a release whose
     * manifest forgot the MSI could never be installed from in the app.
     */
    private fun download(url: String, target: File, expected: String?): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        return runCatching {
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMinutes(20))
                .header("User-Agent", "OpenFlux-Desktop").build()
            val bytes = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray()).body()
            part.writeBytes(bytes)
            if (expected != null) {
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    part.delete()
                    return false
                }
            }
            part.renameTo(target)
            target.length() > 0
        }.getOrDefault(false)
    }

    /** The release's own manifest, restricted to one file. */
    private fun publishedSha256(version: String, fileName: String): String? = runCatching {
        val body = fetchText("https://github.com/$RELEASE_REPO/releases/download/$DESKTOP_TAG_PREFIX$version/SHA256SUMS.txt")
            ?: return@runCatching null
        body.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) parts[1] to parts[0] else null
        }.firstOrNull { (name, _) -> name.removePrefix("*") == fileName }?.second
    }.getOrNull()

    private fun fetchText(url: String): String? = runCatching {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "OpenFlux-Desktop").build()
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
            .send(request, HttpResponse.BodyHandlers.ofString()).body()
    }.getOrNull()

    companion object {
        /**
         * Where the desktop releases are published.
         *
         * This pointed at the upstream desktop repository, so "О программе" on
         * Windows sent people somewhere the Windows client has never been
         * published - while the Android side already named this repository and
         * carried a comment about exactly that mistake. Both clients now name
         * the repository they are actually released from.
         */
        const val RELEASE_REPO = "imbazyx/OpenFlux"
        const val DESKTOP_TAG_PREFIX = "v"
        const val WINDOWS_INSTALLER_SUFFIX = ".msi"
    }
}

/** Passed to a copy started by restartElevated: it waits for this one to exit. */
const val RELAUNCHED_ARG = "--relaunched"
