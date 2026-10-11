package io.openflux.desktop.model

import kotlinx.serialization.Serializable

/** Where a profile came from; shown in the list and details. */
@Serializable
enum class ProfileSource(val label: String) { Manual("Вручную"), Link("Ссылка openflux://"), Qr("QR-код"), Node("Своя нода") }

/** One extra carrier of a Session profile. */
@Serializable
data class ExtraTransport(
    val type: TransportType,
    val value: String = "",
    val uid: String = "",
    val priority: Int = 50,
)

/**
 * A saved connection: which exit to reach and how. Mirrors the Android app's
 * profile so `openflux://` links mean the same on both.
 *
 * In Session mode (the core's --negotiate) the main transport is joined by
 * [extras], all running at once with failover by priority; [context] is the
 * exit's encryption context from an imported link ("" derives it).
 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val icon: String = "ic_public",
    val transport: TransportType = TransportType.VYANDEX,
    /** Document URL, host:port for direct, MAX Web token for oneme. */
    val value: String = "",
    /** MAX user id (oneme only). */
    val uid: String = "",
    val secret: String = "",
    val codec: Codec = Codec.BATCHED,
    val session: Boolean = false,
    val priority: Int = 100,
    val context: String = "",
    val extras: List<ExtraTransport> = emptyList(),
    val source: ProfileSource = ProfileSource.Manual,
    val createdAt: Long = 0,
) {
    /** Every carrier of the profile, main first. */
    val carriers: List<ExtraTransport>
        get() = listOf(ExtraTransport(transport, value, uid, priority)) + if (session) extras else emptyList()

    val summary: String
        get() = if (session) carriers.joinToString(" + ") { it.type.shortLabel } else transport.label

    /** Problems that keep the profile from connecting, empty when it can. */
    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("Укажите название")
        carriers.forEachIndexed { index, carrier ->
            val where = if (index == 0) "Основной транспорт" else "Транспорт ${index + 1}"
            carrierProblem(carrier)?.let { add("$where: $it") }
        }
        // The LENGTH is counted on the trimmed key, because that is what the
        // desktop core measures (main.go:713 TrimSpace, then len < 16 is fatal).
        // The "is there a key at all" test stays on the raw field: a classic
        // profile whose key is 16 spaces passes the trimmed length and still has
        // no key, and quietly reporting "всё в порядке" for it sent the user to
        // a raw start failure instead.
        val key = secret.trim()
        if (session && key.length < MIN_SECRET) add("Для режима Session нужен ключ не короче $MIN_SECRET символов")
        if (!session && secret.isNotEmpty() && key.length < MIN_SECRET) add("Ключ должен быть не короче $MIN_SECRET символов")
        if (!session && transport.sessionOnly) add("${transport.label} работает только в режиме Session")
    }

    /**
     * "This does not look like a key", said once and without blocking anything.
     *
     * NOT part of [problems], on purpose. The core hashes any secret of
     * [MIN_SECRET] characters or more (transport/encrypted.go:77), so a key that
     * does not match either shape still works - a node installed by hand with
     * `openssl rand -base64 48`, a passphrase from a server room. Gating the
     * connect on the shape would refuse working setups over a guess; the editor
     * shows this instead, where the user sees it and decides.
     */
    fun keyShapeWarning(): String? =
        if (secret.trim().length >= MIN_SECRET && !isSecretKey(secret)) {
            "Ключ выглядит не так, как ключи OpenFlux ($SECRET_KEY_SHAPE). Если подключение работает — всё в порядке."
        } else {
            null
        }

    /**
     * The transports as the core's Session names them: after their type, then
     * type-2, type-3 for repeats, so they match the exit's names (cookie
     * exchange is addressed by name).
     */
    fun sessionSpecs(): List<SessionSpec> {
        val seen = mutableMapOf<TransportType, Int>()
        return carriers.map { carrier ->
            val n = (seen[carrier.type] ?: 0) + 1
            seen[carrier.type] = n
            SessionSpec(
                name = if (n == 1) carrier.type.cliName else "${carrier.type.cliName}-$n",
                type = carrier.type,
                value = carrier.value.trim(),
                uid = carrier.uid.trim(),
                priority = carrier.priority,
            )
        }
    }

    /**
     * The encryption context both peers derive keys from: the imported one,
     * else what the core derives, the URL of the highest-priority transport
     * that has one ("http://#" when none does). Cups.online is left out: its
     * room list only exists once the exit is up.
     */
    fun effectiveContext(): String {
        if (context.isNotBlank()) return context
        return sessionSpecs().sortedByDescending { it.priority }
            .firstOrNull { it.type.kind == ValueKind.DocumentUrl && it.type != TransportType.CUPSONLINE && it.value.isNotBlank() }?.value
            ?: "http://#"
    }

    /** The link another device scans; null with why when it cannot be shared. */
    fun toShare(): Result<ShareConfig> = runCatching {
        require(transport.shareable) { "${transport.label} нельзя передать ссылкой: токен привязан к аккаунту" }
        val transports = carriers.filter { it.type.shareable }.map {
            ShareTransport(
                type = it.type.cliName,
                url = if (it.type == TransportType.DIRECT) "" else it.value.trim(),
                dial = if (it.type == TransportType.DIRECT) it.value.trim() else "",
                priority = it.priority,
            )
        }
        ShareConfig(
            name = name,
            negotiate = session,
            codec = codec.cliName,
            // Trimmed: a key pasted from a file keeps its trailing newline, and
            // one that travels through a link would be re-imported with it
            // every time - and mobile does not trim what it is handed.
            secret = secret.trim(),
            context = if (session) effectiveContext() else "",
            transports = transports,
        )
    }

    companion object {
        const val MIN_SECRET = 16

        val ICONS = listOf(
            "ic_public", "ic_link", "ic_lock", "ic_key", "ic_power",
            "ic_person", "ic_swap", "ic_terminal", "ic_apps", "ic_settings",
        )

        fun carrierProblem(carrier: ExtraTransport): String? {
            val value = carrier.value.trim()
            return when (carrier.type.kind) {
                ValueKind.DocumentUrl -> when {
                    value.isEmpty() -> "нужна ссылка"
                    carrier.type != TransportType.CUPSONLINE && !value.startsWith("https://") ->
                        "ссылка должна начинаться с https://"
                    else -> null
                }
                ValueKind.Address -> if (!Regex("""^[^\s:]+:\d{1,5}$""").matches(value)) "нужен адрес host:port" else null
                ValueKind.Token -> when {
                    value.isEmpty() -> "нужен токен MAX"
                    carrier.uid.isBlank() -> "нужен ID пользователя MAX"
                    else -> null
                }
            }
        }

        /**
         * Builds a profile from an `openflux://` link: the highest-priority
         * non-direct transport becomes the main one, the rest Session extras.
         */
        fun fromShare(config: ShareConfig, id: String, now: Long, source: ProfileSource): Profile {
            val sorted = config.transports.sortedByDescending { it.priority }
            val main = sorted.firstOrNull { it.type != TransportType.DIRECT.cliName } ?: sorted.first()
            fun value(t: ShareTransport) = if (t.type == TransportType.DIRECT.cliName) t.dial else t.url
            fun type(t: ShareTransport) = TransportType.fromCli(t.type)
                ?: throw IllegalArgumentException("Неизвестный транспорт ${t.type}")
            return Profile(
                id = id,
                name = config.name.ifBlank { "OpenFlux" },
                transport = type(main),
                value = value(main),
                // Trimmed like toShare(): a link built by hand, or by a build from before
                // that trim, carries the newline of a key file - and mobile
                // hands whatever it is given straight to the core.
                secret = config.secret.trim(),
                codec = if (config.codec == Codec.LEGACY.cliName) Codec.LEGACY else Codec.BATCHED,
                session = config.negotiate,
                priority = main.priority.takeIf { it != 0 } ?: 50,
                context = if (config.negotiate) config.context else "",
                extras = sorted.filter { it !== main }.map { ExtraTransport(type(it), value(it), priority = it.priority) },
                source = source,
                createdAt = now,
            )
        }
    }
}

/** One transport of a Session, as the core's .conf describes it. */
data class SessionSpec(
    val name: String,
    val type: TransportType,
    val value: String,
    val uid: String,
    val priority: Int,
)
