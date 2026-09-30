package io.openflux.desktop.service

import androidx.compose.runtime.staticCompositionLocalOf
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.CaptchaPrompt
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.ExitAddress
import io.openflux.desktop.model.LogLine
import io.openflux.desktop.model.NewChannel
import io.openflux.desktop.model.NodePlan
import io.openflux.desktop.model.ServerProbe
import io.openflux.desktop.model.SshTarget
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.model.TrafficStats
import io.openflux.desktop.model.YandexDocument
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Saved profiles. Writes are persisted before the flow updates. */
interface ProfileRepository {
    val profiles: StateFlow<List<Profile>>
    fun upsert(profile: Profile)
    fun delete(id: String)
    fun newId(): String
}

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    fun update(transform: (AppSettings) -> AppSettings)
}

/** Runs the OpenFlux core for one profile at a time. */
interface ConnectionService {
    val state: StateFlow<ConnectionState>
    val traffic: StateFlow<TrafficStats>
    val exitAddress: StateFlow<ExitAddress>
    val logs: StateFlow<List<LogLine>>
    val captcha: StateFlow<CaptchaPrompt?>
    /** Exit mode: the `openflux://` link the core printed for clients. */
    val exitShareLink: StateFlow<String?>
    /** The local SOCKS5 address while connected as a client. */
    val socksAddress: StateFlow<String?>
    /** The Yandex check in the built-in browser while [captcha] is open. */
    val captchaPage: StateFlow<BrowserPage?>

    fun connect(profile: Profile)
    fun disconnect()
    fun refreshExitAddress()
    fun clearLogs()

    fun openCaptcha()
    fun submitCaptcha()
    fun dismissCaptcha()

    /** Stops the core and undoes system changes; called once on app exit. */
    fun shutdown()
}

/** Where the app runs; decides wording and touch-sized controls. */
enum class PlatformKind { Desktop, Android }

/** Platform facilities the UI needs without touching the platform itself. */
interface PlatformServices {
    val kind: PlatformKind get() = PlatformKind.Desktop
    val appVersion: String
    val coreVersion: String
    /**
     * Where this app's own releases are published, e.g. "imbazyx/OpenFlux".
     *
     * This is the repository the app was actually released from, and it drives
     * both the About links and the update check. A value that still names the
     * upstream repository sends people somewhere this build has never been
     * published, and makes the update check look for releases that do not
     * contain it.
     */
    val clientRepo: String
    /** Whether this OS can point its system proxy at OpenFlux. */
    val systemProxySupported: Boolean
    /** Whether the full tunnel (all traffic through a Wintun adapter) runs on this OS. */
    val fullTunnelSupported: Boolean
    /** Whether OpenFlux runs with administrator rights, which the full tunnel needs. */
    val elevated: Boolean

    /** Starts OpenFlux again as administrator (UAC) and exits this copy; false if that did not happen. */
    fun restartElevated(): Boolean

    fun clipboardText(): String?
    fun setClipboardText(text: String)
    /** Whether [qrFromClipboardImage] can find images on the clipboard. */
    val clipboardImageSupported: Boolean get() = true
    /** Text of a QR code in the image on the clipboard, null when none. */
    fun qrFromClipboardImage(): String?
    /** Text of a QR code in an image file ([pickFile]'s result). */
    fun qrFromFile(path: String): String?
    /** A file the user picks (a path or, on Android, a content URI); null if they cancel. */
    suspend fun pickFile(title: String, extensions: List<String>): String?
    /** Whether [scanQr] has a camera to use. */
    val cameraScanSupported: Boolean get() = false
    /** Text of a QR code the camera read; null if the user cancels. */
    suspend fun scanQr(): String? = null
    /** A small text file's contents (an SSH key), null if it cannot be read. */
    fun readTextFile(path: String, maxBytes: Int = 64 * 1024): String?
    /** The QR modules of [text], rows of dark (true) cells. */
    fun qrMatrix(text: String): List<BooleanArray>
    fun openUrl(url: String)

    /**
     * Whether this OS can send only chosen applications through the tunnel.
     * Android can: the VpnService builder takes an allow list. Desktops point
     * the whole system at OpenFlux and have nothing equivalent.
     */
    val perAppSelectionSupported: Boolean get() = false

    /**
     * One line describing the per-app rule that is currently saved, or null
     * where the concept does not exist.
     *
     * Reading this is not free on Android (it resolves each saved package
     * against the PackageManager), so call it keyed on [appSelectionRev] rather
     * than on every recomposition. That flow ticks whenever the rule is written
     * - by the picker, and by the tunnel service when it has to widen the rule -
     * which is what stops this line from going stale the moment the user returns
     * from the picker.
     */
    fun perAppSummary(): String? = null

    /** Bumped on every write to the per-app rule; see [perAppSummary]. */
    val appSelectionRev: StateFlow<Int> get() = MutableStateFlow(0)

    /**
     * Opens the per-app picker; false when this OS has none.
     */
    fun openAppSelector(): Boolean = false

    /** A new random channel key (64 hex characters). */
    fun newSecret(): String
    fun now(): Long
    /** Newest app release tag on GitHub, null when unknown. */
    suspend fun latestRelease(): String?

    /**
     * The newest release, with the APK to install and whether it is actually
     * newer than what is running. Null when GitHub cannot be reached or the
     * release carries no APK - never an error string, so a failed lookup and
     * "no update" cannot be confused by the caller.
     */
    suspend fun checkForUpdate(): AppUpdate? = null

    /**
     * The same answer, but with the difference between "checked and there is
     * nothing new" and "could not check" kept in it.
     *
     * [checkForUpdate] returns null for both, and the settings screen then
     * shows the same words for a GitHub that answered and one that never did.
     * A user who pressed the button and was told the release was "не найден"
     * had no way to tell a working check from a broken network, and neither
     * did the next person reading their report.
     */
    suspend fun checkForUpdateDetailed(): UpdateCheck = UpdateCheck.Failed("платформа не умеет проверять обновления")

    /**
     * Downloads [update] and hands it to the OS installer; false when this
     * platform cannot install (the desktop build). Whether the user actually
     * accepts the install is not this method's business.
     */
    suspend fun installUpdate(update: AppUpdate): Boolean = false
}

/**
 * What an update check found.
 *
 * Three answers, not two. "Nothing newer", "here is a newer one" and "I could
 * not find out" are different facts about the world, and a UI that draws them
 * all as the same null tells the user something false.
 */
sealed interface UpdateCheck {
    /**
     * The check worked, and [latestVersion] - which is what is running - is
     * the newest thing published. Carrying the version is what lets the screen
     * say "2.2.0" instead of only refusing to offer anything.
     */
    data class UpToDate(val latestVersion: String) : UpdateCheck

    /** A newer release exists and [update] says how to get it. */
    data class Available(val update: AppUpdate) : UpdateCheck

    /**
     * The check did not complete. [reason] is for the user, in their language,
     * and says what went wrong rather than only that something did.
     */
    data class Failed(val reason: String) : UpdateCheck
}

/**
 * A release offered as an in-app update.
 *
 * [version] is the tag without its "v", so it can be compared against
 * [PlatformServices.appVersion] directly.
 */
data class AppUpdate(
    val version: String,
    val downloadUrl: String,
    val versionCode: Int,
    val newer: Boolean,
)

/**
 * The "Своя нода" wizard's server side: installs an independent exit
 * channel on the user's VDS over SSH (the core's --node-wizard), creates
 * the channel's Yandex document in the built-in browser and checks it.
 * Calls block until done and fail with NodeWizardException.
 */
interface NodeWizardService {
    /** SSH in, download the pinned installer and look at the server. */
    suspend fun connect(target: SshTarget): ServerProbe
    suspend fun newChannel(): NewChannel
    /** What installing [channel] would change; port 0 lets the server pick. */
    suspend fun plan(channel: String, withCookies: Boolean): NodePlan
    /** Install and start the channel. [cookieHeader] "" leaves the node signed out. */
    suspend fun apply(channel: NewChannel, documentUrl: String, port: Int, sudoPassword: String, cookieHeader: String)
    suspend fun remove(channel: String, sudoPassword: String)
    /** Whether the node can use the document (edit by link), as an anonymous visitor. */
    suspend fun checkDocument(documentUrl: String)
    /** The channel's `openflux://` link: the document, direct to host:port as backup. */
    suspend fun shareLink(name: String, documentUrl: String, key: String, host: String, port: Int): String
    /** The addresses [host] resolves to, to compare with the tunnel's exit. */
    suspend fun resolve(host: String): Set<String>

    /** The Yandex page while [createDocument] runs. */
    val documentPage: StateFlow<BrowserPage?>

    /**
     * Opens Yandex in the built-in browser (downloaded on first use) for the
     * user to sign in, then creates /openflux/[fileName] on their Disk with
     * edit access by link. [onStep] reports progress. The sign-in is wiped
     * from the browser afterwards; only the returned cookies keep it.
     */
    suspend fun createDocument(fileName: String, onStep: (String) -> Unit): YandexDocument
    fun cancelDocument()

    /** Ends the SSH session and the helper process. */
    fun close()
}

/** Everything the UI depends on, built once in main. */
class AppContainer(
    val profiles: ProfileRepository,
    val settings: SettingsRepository,
    val connection: ConnectionService,
    val platform: PlatformServices,
    val shareCodec: ShareLinkCodec,
    val nodeWizard: NodeWizardService,
) {
    /** An `openflux://` link opened from outside (a scanned code, a chat); the Profiles screen imports it. */
    val incomingLink = MutableStateFlow<String?>(null)
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer is not provided") }
