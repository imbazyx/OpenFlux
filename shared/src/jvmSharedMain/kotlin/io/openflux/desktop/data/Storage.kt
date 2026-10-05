package io.openflux.desktop.data

import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.migrated
import io.openflux.desktop.model.Profile
import io.openflux.desktop.service.ProfileRepository
import io.openflux.desktop.service.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal val StoreJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

/** A JSON file written atomically: a temp file renamed over the old one. */
internal class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {

    /**
     * What is on disk: nothing, a value, or a file this build cannot read.
     *
     * The three used to be one null, which is how a settings file that no
     * longer decoded turned into factory defaults with no word to anyone - and
     * how one retired value in profiles.json erased every saved profile
     * together with its channel key.
     */
    sealed interface Read<out T> {
        data object Absent : Read<Nothing>
        data class Ok<T>(val value: T) : Read<T>

        /**
         * The file is there and this build cannot make sense of it. Callers
         * must not write over it: those bytes are the only copy of the user's
         * profiles and keys.
         */
        data class Unreadable(val message: String) : Read<Nothing>
    }

    fun read(): Read<T> {
        if (!file.isFile) return Read.Absent
        val text = runCatching { file.readText() }.getOrElse { return Read.Unreadable(it.message ?: "не читается") }
        return runCatching { StoreJson.decodeFromString(serializer, text) }
            .fold(
                onSuccess = { Read.Ok(it) },
                onFailure = { Read.Unreadable(it.message ?: "неизвестная ошибка разбора") },
            )
    }

    /**
     * The saved file layered over [defaults], field by field.
     *
     * A key the file does not mention is neither a key the user set nor the
     * field's declared default. Passing the whole defaults object as a fallback
     * only worked when the file did not exist at all, so a settings.json
     * predating a field silently took the declared default instead of the
     * platform's - which is how Android left VPN mode for a SOCKS5-only proxy
     * the user had never asked for.
     */
    fun readOver(defaults: T): Read<T> {
        if (!file.isFile) return Read.Ok(defaults)
        val text = runCatching { file.readText() }.getOrElse { return Read.Unreadable(it.message ?: "не читается") }
        return runCatching {
            val base = StoreJson.encodeToJsonElement(serializer, defaults).jsonObject
            val saved = StoreJson.parseToJsonElement(text).jsonObject
            StoreJson.decodeFromJsonElement(serializer, JsonObject(base + saved))
        }.fold(
            onSuccess = { Read.Ok(it) },
            onFailure = {
                // Keep the original: a user whose profiles stopped reading can
                // hand the file to whoever maintains the build. One that was
                // silently replaced with "[]" is worth nothing to anyone.
                runCatching { file.copyTo(File(file.parentFile, file.name + ".bad"), overwrite = true) }
                Read.Unreadable(it.message ?: "неизвестная ошибка разбора")
            },
        )
    }

    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(StoreJson.encodeToString(serializer, value))
        restrictToOwner(tmp)
        // ATOMIC_MOVE is not available on every file system a config directory
        // can sit on - a synced folder, exFAT, a share - and the exception used
        // to escape into whichever UI coroutine happened to be saving a
        // setting. The plain replace is not atomic but is still correct, and a
        // saved setting beats a crashed screen.
        runCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }
}

/** Makes a file readable by its owner only where the file system allows it. */
internal fun restrictToOwner(file: File) {
    file.setReadable(false, false)
    file.setReadable(true, true)
    file.setWritable(false, false)
    file.setWritable(true, true)
}

class FileProfileRepository(dir: File) : ProfileRepository {
    private val store = JsonFile(File(dir, "profiles.json"), ListSerializer(Profile.serializer()))

    /**
     * Set when profiles.json is present but unreadable.
     *
     * While it is, saves are refused. The in-memory list is empty only because
     * the file could not be parsed, and writing that empty list back would
     * destroy the very profiles and keys the parse failed to reach.
     */
    var unreadable: String? = null
        private set

    private val locked: Boolean get() = unreadable != null

    private val state: MutableStateFlow<List<Profile>>

    init {
        when (val read = store.read()) {
            is JsonFile.Read.Ok -> state = MutableStateFlow(read.value)
            is JsonFile.Read.Unreadable -> {
                state = MutableStateFlow(emptyList())
                unreadable = "Сохранённые профили не читаются (${read.message}). Файл сохранён как profiles.json.bad и не перезаписан."
            }
            JsonFile.Read.Absent -> state = MutableStateFlow(emptyList())
        }
    }

    override val profiles: StateFlow<List<Profile>> = state.asStateFlow()

    @Synchronized
    override fun upsert(profile: Profile) {
        if (locked) return
        val current = state.value
        val next = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        store.write(next)
        state.value = next
    }

    @Synchronized
    override fun delete(id: String) {
        if (locked) return
        val next = state.value.filterNot { it.id == id }
        store.write(next)
        state.value = next
    }

    override fun newId(): String = UUID.randomUUID().toString()
}

class FileSettingsRepository(dir: File, defaults: AppSettings = AppSettings()) : SettingsRepository {
    private val store = JsonFile(File(dir, "settings.json"), AppSettings.serializer())

    /**
     * Set when settings.json is present but unreadable.
     *
     * While it is, saves are refused, for the reason
     * [FileProfileRepository.unreadable] gives: the in-memory settings are
     * only the defaults because the real ones could not be parsed, so writing
     * them back would destroy the user's server host, port, and the master
     * keys of every node of their own. The file is left exactly as it is and
     * kept aside as settings.json.bad, so it can still be recovered by hand.
     *
     * The copy was already being made; what was missing is the refusal to
     * overwrite, which is why a corrupt file became a lost file the moment the
     * user changed anything.
     */
    var unreadable: String? = null
        private set

    private val locked: Boolean get() = unreadable != null

    private val state: MutableStateFlow<AppSettings> = MutableStateFlow(
        when (val read = store.readOver(defaults)) {
            is JsonFile.Read.Ok ->
                read.value.sane().let { loaded -> loaded.migrated().also { m -> if (m != loaded) store.write(m) } }
            is JsonFile.Read.Unreadable -> {
                unreadable = "Сохранённые настройки не читаются (${read.message}). Файл сохранён как settings.json.bad и не перезаписан."
                defaults.sane().migrated()
            }
            // Nothing to migrate and nothing to write: the file is the user's,
            // and it stays exactly as it is.
            JsonFile.Read.Absent -> defaults.sane().migrated()
        },
    )
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    @Synchronized
    override fun update(transform: (AppSettings) -> AppSettings) {
        state.update { old ->
            // Only the write is refused, not the change itself. The in-memory
            // value is what the app runs on, and freezing it would mean a user
            // whose settings stopped reading could not so much as switch the
            // theme until they found the file by hand. The change applies this
            // session; it does not survive a restart, because the damaged file
            // is deliberately left in place rather than replaced with it.
            transform(old).also { if (it != old && !locked) store.write(it) }
        }
    }
}

/**
 * Ports inside their usable range.
 *
 * The SOCKS5 port's neighbour is where the HTTP proxy is bound, so a
 * socksPort of 65535 asked for 127.0.0.1:65536 and the core simply refused to
 * start. The settings screen checked the range; a settings.json on disk - which
 * a user can edit and which survives a version change - was never checked.
 */
private fun AppSettings.sane(): AppSettings = copy(
    socksPort = socksPort.coerceIn(1024, 65534),
    exitDirectPort = exitDirectPort.coerceIn(1, 65535),
)
