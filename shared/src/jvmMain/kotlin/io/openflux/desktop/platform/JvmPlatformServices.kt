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
import io.openflux.desktop.service.InstallResult
import io.openflux.desktop.service.UpdateCheck
import io.openflux.desktop.service.PlatformServices
import io.openflux.desktop.web.BrowserLog
import io.openflux.desktop.updates.compareVersions
import io.openflux.desktop.updates.versionCodeOf
import io.openflux.desktop.updates.versionParts
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
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
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
        request("https://github.com/$RELEASE_REPO/releases.atom").body
    }

    /**
     * One request, with the reason kept when it does not work.
     *
     * A result that only says "no answer" is what made this screen lie: a
     * 403 from GitHub's address limit and a flat refusal to report an update
     * both arrived as null and both read as "не найден".
     */
    internal data class Answer(val body: String?, val problem: String?) {
        /**
         * Success is "no problem", NOT "there is a body".
         *
         * Deriving it from the body is what made the desktop update button
         * completely dead: a HEAD request has no body by definition - and
         * `request()` explicitly forces `body = null` when `head = true` - so
         * `ok` was false for every successful probe. The walk over the five
         * newest app releases could never find anything, and the user was told
         *
         *     выпуск v2.3.2 есть, но установщик OpenFlux-2.3.2.msi не отдаётся: null
         *
         * about an asset that had just answered 200, with a literal `null` where
         * the reason should be, because `problem` was null too and Kotlin
         * stringified it. `Available` and `UpToDate` were both unreachable.
         */
        val ok: Boolean get() = problem == null
    }

    /**
     * One HTTP round trip, and what it could not do.
     *
     * HttpURLConnection, not java.net.http. The shipped runtime is a jlink
     * image with eleven modules in it and java.net.http is not one of them, so
     * a reference to HttpTimeoutException anywhere in this class - even in a
     * catch clause, which is part of the method's exception table - makes the
     * class fail to verify, and the app dies at startup with
     * NoClassDefFoundError before it draws anything. HttpURLConnection is in
     * java.base, which is in every runtime there is.
     */
    private fun request(url: String, head: Boolean = false): Answer {
        val connection = runCatching {
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = if (head) "HEAD" else "GET"
                connectTimeout = 8_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OpenFlux-Desktop")
                setRequestProperty("Accept", "*/*")
            }
        }.getOrElse {
            return Answer(null, when (it) {
                is java.net.UnknownHostException -> "не найден адрес github.com"
                is java.net.ConnectException -> "нет связи с интернетом"
                else -> it.message ?: "неизвестная ошибка"
            })
        }
        return try {
            val code = connection.responseCode
            val body = if (head || code !in 200..299) null else connection.inputStream.bufferedReader().readText()
            when {
                code in 200..299 -> Answer(body, null)
                code == 404 -> Answer(null, "на GitHub нет такого файла")
                code == 403 || code == 429 ->
                    Answer(null, "GitHub временно не отвечает (лимит запросов с одного адреса)")
                else -> Answer(null, "GitHub ответил кодом $code")
            }
        } catch (e: SocketTimeoutException) {
            Answer(null, "GitHub не ответил вовремя")
        } catch (e: Exception) {
            Answer(null, e.message ?: e::class.simpleName ?: "неизвестная ошибка")
        } finally {
            connection.disconnect()
        }
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
     * The older [checkForUpdate] is kept for callers that only want the update
     * or nothing, and is now a projection of this one rather than a second
     * implementation that can disagree with it.
     */
    override suspend fun checkForUpdateDetailed(): UpdateCheck {
        val feed = releaseFeed()
            ?: return UpdateCheck.Failed("не удалось прочитать список выпусков с GitHub")
        // App tags only, newest version first, walked until one is found.
        //
        // The first entry of the feed is the newest by CREATION date, and the
        // exit-node CORE releases are published into the same feed. So taking
        // that entry reported a core release and its non-existent MSI - and with
        // no walk there was no way past it, so the desktop updater stayed broken
        // for every user until the next app release was cut. The Android check
        // had this defect first and now carries the same shape.
        var newestAppTag: String? = null
        var newestProblem: String? = null
        for (tag in appReleaseTags(feed).take(DESKTOP_MAX_RELEASES_TO_CHECK)) {
            val version = tag.removePrefix(DESKTOP_TAG_PREFIX)

            // The installer is named after the version by the same rule the
            // build uses, so asking whether the file is really there reports a
            // change to that rule as what it is - "GitHub has no such file" -
            // rather than as "no update", which a user cannot act on.
            val name = "OpenFlux-$version$WINDOWS_INSTALLER_SUFFIX"
            val url = "https://github.com/$RELEASE_REPO/releases/download/$tag/$name"
            val head = withContext(Dispatchers.IO) { request(url, head = true) }
            if (head.ok) {
                val update = AppUpdate(
                    version = version,
                    downloadUrl = url,
                    versionCode = versionCodeOf(version),
                    newer = compareVersions(version, appVersion) > 0,
                )
                // A success on a LATER tag must not paper over a failure on the newest
                // one. v2.3.2's MSI exists but its HEAD got a transient 502
                // from the CDN; the walk continued, v2.3.1 answered, and the
                // screen drew "2.3.1 - последняя версия" - telling the user
                // they are current while a newer release sits there with its
                // failure already computed and thrown away.
                //
                // The older tag is genuinely installable, so it is still
                // returned - refusing would hide a working update. But the
                // newer failure is written where the user will actually see it,
                // because the UpToDate branch has nowhere to carry it.
                if (newestAppTag != null && newestAppTag != tag && newestProblem != null) {
                    val newestName = "OpenFlux-" + newestAppTag.removePrefix(DESKTOP_TAG_PREFIX) +
                        WINDOWS_INSTALLER_SUFFIX
                    BrowserLog.problem("выпуск $newestAppTag есть, но установщик $newestName не отдаётся: $newestProblem")
                }
                return if (update.newer) UpdateCheck.Available(update) else UpdateCheck.UpToDate(version)
            }
            // Keep walking: an older app release may still carry a build this
            // machine can install. Remember why the newest one was skipped.
            if (newestAppTag == null) {
                newestAppTag = tag
                newestProblem = "выпуск $tag есть, но установщик $name не отдаётся: ${head.problem}"
            }
        }
        newestAppTag
            ?: return UpdateCheck.Failed("в списке выпусков нет ни одного выпуска приложения")
        return UpdateCheck.Failed(newestProblem ?: "не удалось найти установщик")
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
     * Downloads the MSI and hands it to Windows Installer.
     *
     * Verified against the release's own SHA256SUMS.txt before msiexec is
     * started, for the reason Android verifies its APK: this file replaces the
     * running program, and a truncated download must not become a silent
     * update.
     *
     * msiexec /i runs the install straight away, and Windows Installer will
     * refuse to replace files the running app holds open. So this starts the
     * install and then closes the app itself, which is why it returns true for
     * "handed over", not "finished".
     *
     * The exit lives here rather than in the shared screen on purpose: only
     * this platform has the constraint, and only this platform can close the
     * window. The screen used to call the installer and then sit there running,
     * so the update could never actually replace anything.
     */
    override suspend fun installUpdate(update: AppUpdate): InstallResult = withContext(Dispatchers.IO) {
        val target = File(System.getProperty("java.io.tmpdir"), "OpenFlux-${update.version}-setup.msi")
        // The manifest is indexed by the PUBLISHED asset name, not by whatever
        // this method happens to call the file on disk. They differ ("-setup"
        // below), so looking up target.name found nothing, `expected` was always
        // null, and every MSI was installed with no hash check at all - silently,
        // because the "installing unverified" warning was only on Android.
        val published = "OpenFlux-${update.version}$WINDOWS_INSTALLER_SUFFIX"
        val expected = publishedSha256(update.version, published)
        val downloaded = download(update.downloadUrl, target, expected, published)
        // shown = false throughout: this platform has no way to raise a toast,
        // so the shared UI is what tells the user, and it needs to know that
        // nothing has been said yet.
        if (!downloaded) return@withContext InstallResult.Refused(shown = false)

        val started = runCatching {
            ProcessBuilder("msiexec", "/i", target.absolutePath)
                .redirectErrorStream(true)
                .start()
            true
        }.getOrDefault(false)
        if (!started) return@withContext InstallResult.Refused(shown = false)

        // Long enough for the installer window to come up on top of us, short
        // enough that the user does not read it as a hang. The shutdown hook
        // registered in AppFactory kills the core and restores the system proxy
        // on this way out, so nothing is left running.
        Thread({
            Thread.sleep(1_500)
            Runtime.getRuntime().exit(0)
        }, "openflux-exit-for-update").apply { isDaemon = true }.start()
        InstallResult.HandedOff(verified = expected != null)
    }

    /**
     * Fetches [url] to [target], checking it against [expected] when the
     * release publishes a checksum for this exact file. A missing manifest
     * entry is a warning, not a refusal: refusing would mean a release whose
     * manifest forgot the MSI could never be installed from in the app.
     *
     * [publishedName] is what the release calls the asset, which is not
     * [target]'s name - it is in the log line for the unverifiable case, and
     * that line is the only trace of it.
     */
    private fun download(url: String, target: File, expected: String?, publishedName: String): Boolean {
        val part = File(target.parentFile, target.name + ".part")
        return runCatching {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20 * 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "OpenFlux-Desktop")
            }
            val bytes = try {
                connection.inputStream.readBytes()
            } finally {
                connection.disconnect()
            }
            part.writeBytes(bytes)
            if (expected == null) {
                // Never silent. Android logs exactly this before installing an
                // APK with no manifest line; on Windows it is not a rare
                // fallback but the only path that ever runs, because
                // release.yml has no desktop job and SHA256SUMS.txt therefore
                // contains no MSI line at all. Without this line an unverified
                // installer is indistinguishable from a verified one.
                BrowserLog.problem(
                    "В выпуске нет контрольной суммы для $publishedName; " +
                        "устанавливается без проверки"
                )
            } else {
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    part.delete()
                    BrowserLog.problem("Контрольная сумма $publishedName не совпала")
                    return false
                }
            }
            // renameTo's result used to be discarded and the function reported
            // success from target.length(). The target name is fixed per version
            // and never deleted, so a stale file from an earlier attempt -
            // held open by a just-started msiexec or a scanner - made a failed
            // rename look like a success, and msiexec was handed a file this
            // round never downloaded.
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                BrowserLog.problem("Не удалось сохранить загруженный установщик: $publishedName")
                return false
            }
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
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OpenFlux-Desktop")
        }
        try {
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
    internal fun newestTag(feed: String): String? =
        Regex("""<link[^>]*href="[^"]*/releases/tag/([^"/]+)"""")
            .find(feed)?.groupValues?.get(1)?.trim()

    /**
     * Every APP release in the feed, newest version first.
     *
     * `newestTag()` above takes the first entry, which is the newest by CREATION
     * date - and this repository publishes its exit-node CORE releases into the
     * same feed. `node-v1.0.0` and `0.0.5` are already in it, carrying no MSI.
     * The moment any core release is cut it becomes the first entry and the
     * desktop updater reports "выпуск node-v1.0.0 есть, но установщик
     * OpenFlux-node-v1.0.0.msi не отдаётся" - with no walk down the list to
     * reach the real release, so it stays broken for every desktop user until
     * the next app release is cut.
     *
     * The Android side had exactly this defect and carries `appReleaseTags()`;
     * this is the same fix, not a new idea.
     */
    internal fun appReleaseTags(feed: String): List<String> =
        Regex("""<link[^>]*href="[^"]*/releases/tag/([^"/]+)"""")
            .findAll(feed)
            .map { it.groupValues[1].trim() }
            // Same shape Android uses. "Starts with v and is longer than v" also
            // accepts `v2.4.0-rc1`, and versionParts drops the suffix - so a
            // pre-release tag sorts ABOVE v2.3.2, takes the first walk slot,
            // fails its probe, and the user is told "выпуск v2.4.0-rc1 есть, но
            // установщик OpenFlux-2.4.0.msi не отдаётся" about a tag this
            // project publishes no assets for.
            .filter { DESKTOP_VERSION_TAG.matches(it) }
            .distinct()
            // versionParts returns List<Int>, which is not Comparable, so the selector
            // returns the padded digits below.
            // selector has to return something that is - the joined digits keep
            // the ordering numeric for every version this project has used
            // (all three components single-digit), and compareVersions below
            // remains the authority for any actual comparison.
            // Padded per component, not simply joined. A plain joinToString("")
            // makes the key a STRING, so "2.3.10" sorts BELOW "2.3.1" (the text
            // "2310" is less than "231") and "10.0.0" sorts last of all ("1000"
            // is less than "900"). Every version this project has shipped has
            // single-digit components, so the key has always been exactly three
            // characters and the two orders have agreed - which is precisely why
            // the landmine at 2.10.0 was invisible. Four digits covers any
            // plausible component.
            .sortedWith(
                compareByDescending<String> {
                    versionParts(it.removePrefix(DESKTOP_TAG_PREFIX))
                        .joinToString("") { part -> part.toString().padStart(4, '0') }
                },
            )
            .toList()
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

    /** Same depth as the Android walk: newest-first, stop after this many. */
    private const val DESKTOP_MAX_RELEASES_TO_CHECK = 5

    /** Exactly `vX.Y.Z` - no suffix, no shorter form. Mirrors APP_VERSION_TAG on Android. */
    private val DESKTOP_VERSION_TAG = Regex("""^v\d+\.\d+\.\d+$""")
        const val WINDOWS_INSTALLER_SUFFIX = ".msi"
    }
}

/** Passed to a copy started by restartElevated: it waits for this one to exit. */
const val RELAUNCHED_ARG = "--relaunched"
