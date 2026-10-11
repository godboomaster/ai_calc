package com.smartcalc.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class ApiSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
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

class SettingsRepository(private val context: Context) {

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val SWIPE_TO_RESTORE = booleanPreferencesKey("swipe_to_restore")
    }

    val swipeToRestore: Flow<Boolean> = context.dataStore.data.map { p ->
        p[Keys.SWIPE_TO_RESTORE] ?: false
    }

    suspend fun setSwipeToRestore(enabled: Boolean) {
        context.dataStore.edit { p -> p[Keys.SWIPE_TO_RESTORE] = enabled }
    }

    val settings: Flow<ApiSettings> = context.dataStore.data.map { p ->
        ApiSettings(
            baseUrl = p[Keys.BASE_URL] ?: ApiSettings.DEFAULT_BASE_URL,
            apiKey = p[Keys.API_KEY] ?: "",
            model = p[Keys.MODEL] ?: ApiSettings.DEFAULT_MODEL,
            systemPrompt = p[Keys.SYSTEM_PROMPT] ?: ApiSettings.DEFAULT_SYSTEM_PROMPT,
        )
    }

    suspend fun save(s: ApiSettings) {
        context.dataStore.edit { p ->
            p[Keys.BASE_URL] = s.baseUrl
            p[Keys.API_KEY] = s.apiKey
            p[Keys.MODEL] = s.model
            p[Keys.SYSTEM_PROMPT] = s.systemPrompt
        }
    }
}
