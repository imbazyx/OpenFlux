package io.openflux.android.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import io.openflux.android.MainActivity
import io.openflux.android.R
import io.openflux.android.openFlux

/**
 * The foreground service the core runs under while connected: the VPN
 * (the TUN interface only a VpnService may create), the local proxy or the
 * exit node. [AndroidConnectionService] drives it; this class only holds
 * what must belong to a service.
 */
class CoreService : VpnService() {
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Set by [applyAppSelection] when it had to widen the rule to a full tunnel.
     * Rewriting the stored preference makes the STATE honest, but the user is
     * not looking at a settings screen while the VPN comes up - they are looking
     * at "Подключено". Without this the one outcome that matters most (the whole
     * phone now goes through the node) is silent. Read once by
     * AndroidConnectionService right after establish() and shown in the status
     * line.
     */
    @Volatile
    var appRuleNotice: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val connection = openFlux.connection
        if (intent?.action == ACTION_STOP) {
            connection.disconnect()
            return START_NOT_STICKY
        }
        // startForegroundService() must be answered with startForeground() right away.
        createChannels(this)
        val notification = notification("Подключение…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        connection.onServiceStarted(this)
        return START_NOT_STICKY
    }

    /** Another app took the VPN over, or the user turned it off in the system settings. */
    override fun onRevoke() {
        openFlux.connection.onVpnRevoked()
    }

    override fun onDestroy() {
        releaseWakeLock()
        openFlux.connection.onServiceDestroyed(this)
        super.onDestroy()
    }

    /**
     * The TUN interface: all IPv4 through it, DNS to [dns] (answered by the
     * packet tunnel), this app itself outside, since the core's own traffic
     * must not loop back into the tunnel.
     *
     * [AppSelection] narrows that to chosen applications when the user asked
     * for it: the TUN then carries only those, and the rest of the phone keeps
     * its normal route.
     */
    fun establish(mtu: Int, dns: String): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("OpenFlux")
            .setMtu(mtu)
            .addAddress("10.10.10.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(dns)
            .setConfigureIntent(openAppIntent(this))
        // Cleared first so a previous run's notice cannot leak into this one.
        appRuleNotice = null
        applyAppSelection(builder)
        if (Build.VERSION.SDK_INT >= 29) builder.setBlocking(true)
        return builder.establish()
    }

    /**
     * Builds the per-app rule. Reading it here, once per interface, keeps a
     * change in the settings from pulling the tunnel out under a running
     * connection: it applies on the next connect.
     *
     * The rule is an ALLOW list of the chosen packages, which is what
     * Builder.addAllowedApplication documents: "only applications added through
     * this method (and no others) are allowed access". Everything not named -
     * including this app - keeps using the network as if the VPN were not
     * running, so the core's Mail.ru control channel stays outside the tunnel
     * on its own and never needs VpnService.protect(). An allow list also
     * matches what the settings screen promises: an app installed after the
     * last save, or one in a work profile, simply stays direct, instead of
     * being swept into the tunnel by a deny list built from a snapshot.
     *
     * Builder accepts one kind of list or the other, never both: mixing
     * addAllowedApplication and addDisallowedApplication throws
     * UnsupportedOperationException. The full-tunnel branch therefore returns
     * before any allow call happens.
     */
    private fun applyAppSelection(builder: Builder) {
        val context = applicationContext
        val fullTunnel = {
            // The full tunnel excludes only ourselves, exactly as before.
            builder.addDisallowedApplication(packageName)
        }
        if (!AppSelection.onlySelected(context)) {
            fullTunnel()
            return
        }
        // Resolve the saved names before building the list. An allow list is
        // only active if addAllowedApplication ran at least once: never called
        // it means "no list", which Android reads as everything allowed. So a
        // rule whose apps have all been uninstalled must fall back to a full
        // tunnel rather than quietly degenerating into one of the two broken
        // states - a silent no-op tunnel, or a per-app mode with no apps.
        val saved = AppSelection.selected(context)
        val live = saved.filter { pkg ->
            // Never allow our own package: routing it into our own TUN would
            // make PacketTunnel.readOutgoing() feed the core's Mail.ru
            // connection to Mobile.send(), which writes the tunnel envelope
            // into the very socket whose packets come back to the TUN.
            pkg != packageName &&
                runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess
        }
        if (live.isEmpty()) {
            Log.w(
                "OpenFluxVPN",
                "per-app rule: none of ${saved.size} saved apps are installed, " +
                    "falling back to a full tunnel",
            )
            // Degrade, but never silently. A full tunnel sends the WHOLE phone
            // through the node - the exact opposite of what the user asked for
            // by enabling per-app mode - so the stored rule is rewritten to
            // match what the tunnel is really doing. Leaving the preference
            // saying "only 2 apps" while every app is routed would be a privacy
            // failure with no on-screen trace anywhere.
            AppSelection.save(context, false, emptySet())
            appRuleNotice =
                "Выбранных приложений больше нет — через ноду идёт весь трафик телефона"
            fullTunnel()
            return
        }
        var failed = 0
        for (pkg in live) {
            runCatching { builder.addAllowedApplication(pkg) }
                .onFailure {
                    failed++
                    Log.w("OpenFluxVPN", "cannot allow $pkg through the tunnel", it)
                }
        }
        if (failed == live.size) {
            // addAllowedApplication throws from verifyApp BEFORE it touches the
            // builder, so the allow list is still null and addDisallowedApplication
            // is still legal: this takes the same honest path as the empty case
            // rather than leaving Android to read "no list" as "everything".
            Log.w(
                "OpenFluxVPN",
                "per-app rule: the system rejected all ${live.size} apps, " +
                    "falling back to a full tunnel",
            )
            AppSelection.save(context, false, emptySet())
            appRuleNotice =
                "Система не приняла ни одного выбранного приложения — через ноду идёт весь трафик"
            fullTunnel()
            return
        }
        Log.i(
            "OpenFluxVPN",
            "per-app mode: ${live.size - failed} apps through the tunnel, " +
                "$failed rejected by the system",
        )
    }

    /** An exit node keeps serving clients with the screen off. */
    fun holdWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenFlux:exit")
            .apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    fun finish() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CoreService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CORE_CHANNEL)
            .setContentTitle("OpenFlux")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_openflux_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(this))
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_power), "Отключить", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "io.openflux.android.STOP"
        private const val NOTIFICATION_ID = 7
        private const val CAPTCHA_NOTIFICATION_ID = 9
        private const val CORE_CHANNEL = "openflux_core"
        private const val CAPTCHA_CHANNEL = "openflux_captcha"

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CORE_CHANNEL, "Подключение OpenFlux", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CAPTCHA_CHANNEL, "Проверка Яндекса", NotificationManager.IMPORTANCE_HIGH))
        }

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** A Yandex check waits while the app is in the background: tapping opens it. */
        fun notifyCaptcha(context: Context, remote: Boolean, login: Boolean) {
            createChannels(context)
            val title = when {
                remote && login -> "OpenFlux: ноде нужен вход в Яндекс"
                remote -> "OpenFlux: нода просит пройти проверку"
                login -> "OpenFlux: нужен вход в Яндекс"
                else -> "OpenFlux: нужна проверка Яндекса"
            }
            val notification = Notification.Builder(context, CAPTCHA_CHANNEL)
                .setContentTitle(title)
                .setContentText("Нажмите, чтобы открыть проверку")
                .setSmallIcon(R.drawable.ic_openflux_notification)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(CAPTCHA_NOTIFICATION_ID, notification) }
        }

        fun cancelCaptcha(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(CAPTCHA_NOTIFICATION_ID)
        }
    }
}
