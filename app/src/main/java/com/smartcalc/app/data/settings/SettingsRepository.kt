package com.smartcalc.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartcalc.app.data.api.displayName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * How the provider expects the request: which reasoning field it takes and its tool rules.
 * Stored by name; AUTO picks one by the API address.
 */
enum class ApiFormat { AUTO, THINKING_TYPE, ENABLE_THINKING, REASONING_EFFORT, SERVER_DEFAULT }

/** A saved provider + model; the active one supplies [ApiSettings] for every request. */
@Serializable
data class ApiConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val baseUrl: String = ApiSettings.DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = ApiSettings.DEFAULT_MODEL,
    val apiFormat: ApiFormat = ApiFormat.AUTO,
    val supportsImages: Boolean = false,
    val thinkingEnabled: Boolean = true,
)

/** Always at least one connection; [active] falls back to the first if the saved ID is gone. */
data class ConnectionList(val items: List<ApiConnection>, val activeId: String) {
    val active: ApiConnection get() = items.firstOrNull { it.id == activeId } ?: items.first()
}

/** What a request needs: the active connection plus the shared solving settings. */
data class ApiSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val supportsImages: Boolean = false,
    val libraryToolsEnabled: Boolean = false,
    /** Перед отправкой подобрать похожие задачи библиотеки и дать отметить подходящие. */
    val similarTasksEnabled: Boolean = true,
    val thinkingEnabled: Boolean = true,
    val apiFormat: ApiFormat = ApiFormat.AUTO,
    val connectionName: String = "",
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"
        val DEFAULT_SYSTEM_PROMPT = """
            Ты — эксперт по решению математических и учебных задач. Пользователь присылает фото или текст задачи.
            Распознай условие и реши задачу. Отвечай СТРОГО одним JSON-объектом, без пояснений и без markdown-обёртки:
            {"problem": "распознанное условие", "answer": "краткий ответ", "steps": [{"title": "краткое название шага", "content": "подробное объяснение шага"}]}
            Если пользователь указал, каким способом решать или как оформить решение, — строго следуй этому.
            Всё пиши на русском языке.
        """.trimIndent()
    }
}

/** The same solving settings sent through [connection]. */
fun ApiSettings.withConnection(connection: ApiConnection) = copy(
    baseUrl = connection.baseUrl,
    apiKey = connection.apiKey,
    model = connection.model,
    supportsImages = connection.supportsImages,
    thinkingEnabled = connection.thinkingEnabled,
    apiFormat = connection.apiFormat,
    connectionName = connection.displayName,
)

/** Код кнопки ✦ (пустой — кнопка видна всегда) и открыта ли она этим кодом сейчас. */
data class AssistantLock(val code: String = "", val revealed: Boolean = false)

private val connectionJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }
private val connectionsSerializer = ListSerializer(ApiConnection.serializer())

internal fun encodeConnections(items: List<ApiConnection>): String = connectionJson.encodeToString(connectionsSerializer, items)

/** A damaged list yields null, so the caller keeps the previous single-connection settings. */
internal fun decodeConnections(text: String): List<ApiConnection>? =
    runCatching { connectionJson.decodeFromString(connectionsSerializer, text) }.getOrNull()?.takeIf { it.isNotEmpty() }

/** Before connections existed, one set of API settings was stored in separate keys. */
internal const val LEGACY_CONNECTION_ID = "legacy"

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val CONNECTIONS = stringPreferencesKey("connections")
        val ACTIVE_CONNECTION = stringPreferencesKey("active_connection")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val LIBRARY_TOOLS = booleanPreferencesKey("library_tools")
        val SIMILAR_TASKS = booleanPreferencesKey("similar_tasks")
        val SWIPE_TO_RESTORE = booleanPreferencesKey("swipe_to_restore")
        val ASSISTANT_CODE = stringPreferencesKey("assistant_code")
        val ASSISTANT_REVEALED = booleanPreferencesKey("assistant_revealed")

        // Single-connection settings; moved into CONNECTIONS on the first write.
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val SUPPORTS_IMAGES = booleanPreferencesKey("supports_images")
        val THINKING = booleanPreferencesKey("thinking_enabled")
        val REASONING_FORMAT = stringPreferencesKey("reasoning_format")
        val LEGACY = listOf(BASE_URL, API_KEY, MODEL, SUPPORTS_IMAGES, THINKING, REASONING_FORMAT)
    }

    val swipeToRestore: Flow<Boolean> = store.data.map { p ->
        p[Keys.SWIPE_TO_RESTORE] ?: false
    }

    suspend fun setSwipeToRestore(enabled: Boolean) {
        store.edit { p -> p[Keys.SWIPE_TO_RESTORE] = enabled }
    }

    /** Код и состояние кнопки ✦ читаются вместе: при запуске кнопка не мелькает между ними. */
    val assistantLock: Flow<AssistantLock> = store.data.map { p ->
        AssistantLock(code = p[Keys.ASSISTANT_CODE] ?: "", revealed = p[Keys.ASSISTANT_REVEALED] ?: false)
    }

    /** Новый код сразу кнопку не прячет; без кода состояние не хранится. */
    suspend fun setAssistantCode(code: String) {
        store.edit { p ->
            if (code.isEmpty()) {
                p.remove(Keys.ASSISTANT_CODE)
                p.remove(Keys.ASSISTANT_REVEALED)
            } else {
                p[Keys.ASSISTANT_CODE] = code
                p[Keys.ASSISTANT_REVEALED] = true
            }
        }
    }

    /** Код набран на калькуляторе: кнопка ✦ показывается или прячется до следующего набора, и после перезапуска. */
    suspend fun toggleAssistantRevealed() {
        store.edit { p -> p[Keys.ASSISTANT_REVEALED] = !(p[Keys.ASSISTANT_REVEALED] ?: false) }
    }

    /** Экстренное скрытие: ✦ прячется до следующего набора кода. Без кода прятать нечего. */
    suspend fun concealAssistant() {
        store.edit { p -> if (p[Keys.ASSISTANT_CODE] != null) p[Keys.ASSISTANT_REVEALED] = false }
    }

    val connections: Flow<ConnectionList> = store.data.map(::connectionList)

    val settings: Flow<ApiSettings> = store.data.map { p ->
        ApiSettings(
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: ApiSettings.DEFAULT_SYSTEM_PROMPT,
            libraryToolsEnabled = p[Keys.LIBRARY_TOOLS] ?: false,
            similarTasksEnabled = p[Keys.SIMILAR_TASKS] ?: true,
        ).withConnection(connectionList(p).active)
    }

    /** null в [similarTasksEnabled] оставляет подбор похожих задач как был. */
    suspend fun saveSolving(systemPrompt: String, libraryToolsEnabled: Boolean, similarTasksEnabled: Boolean? = null) {
        store.edit { p ->
            p[Keys.SYSTEM_PROMPT] = systemPrompt
            p[Keys.LIBRARY_TOOLS] = libraryToolsEnabled
            similarTasksEnabled?.let { p[Keys.SIMILAR_TASKS] = it }
        }
    }

    /** Adds or replaces by ID. */
    suspend fun saveConnection(connection: ApiConnection, activate: Boolean = false) = editConnections { list ->
        val exists = list.items.any { it.id == connection.id }
        ConnectionList(
            if (exists) list.items.map { if (it.id == connection.id) connection else it } else list.items + connection,
            if (activate) connection.id else list.activeId,
        )
    }

    suspend fun selectConnection(id: String) = editConnections { list ->
        if (list.items.any { it.id == id }) list.copy(activeId = id) else list
    }

    /** The last connection stays: requests always need one. */
    suspend fun deleteConnection(id: String) = editConnections { list ->
        val rest = list.items.filterNot { it.id == id }
        if (rest.isEmpty()) list else ConnectionList(rest, if (list.activeId == id) rest.first().id else list.activeId)
    }

    private suspend fun editConnections(change: (ConnectionList) -> ConnectionList) {
        store.edit { p ->
            val updated = change(connectionList(p))
            p[Keys.CONNECTIONS] = encodeConnections(updated.items)
            p[Keys.ACTIVE_CONNECTION] = updated.active.id
            // The key now lives only in the list.
            removeLegacy(p)
        }
    }

    private fun removeLegacy(p: MutablePreferences) = Keys.LEGACY.forEach { p.remove(it) }

    private fun connectionList(p: Preferences): ConnectionList {
        val items = p[Keys.CONNECTIONS]?.let(::decodeConnections) ?: listOf(legacyConnection(p))
        return ConnectionList(items, p[Keys.ACTIVE_CONNECTION] ?: items.first().id)
    }

    private fun legacyConnection(p: Preferences) = ApiConnection(
        id = LEGACY_CONNECTION_ID,
        baseUrl = p[Keys.BASE_URL] ?: ApiSettings.DEFAULT_BASE_URL,
        apiKey = p[Keys.API_KEY] ?: "",
        model = p[Keys.MODEL] ?: ApiSettings.DEFAULT_MODEL,
        apiFormat = ApiFormat.entries.firstOrNull { it.name == p[Keys.REASONING_FORMAT] } ?: ApiFormat.AUTO,
        supportsImages = p[Keys.SUPPORTS_IMAGES] ?: false,
        thinkingEnabled = p[Keys.THINKING] ?: true,
    )
}
