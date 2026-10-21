package com.smartcalc.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConnectionSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun storeTest(block: suspend (SettingsRepository, DataStore<Preferences>) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } }
            block(SettingsRepository(store), store)
        } finally { scope.cancel() }
    }

    @Test fun singleConnectionSettingsBecomeFirstConnectionAndKeyIsStoredOnlyOnce() = storeTest { repo, store ->
        store.edit { p ->
            p[stringPreferencesKey("base_url")] = "https://api.deepseek.com"
            p[stringPreferencesKey("api_key")] = "fixture-key"
            p[stringPreferencesKey("model")] = "deepseek-flash"
            p[booleanPreferencesKey("supports_images")] = true
            p[booleanPreferencesKey("thinking_enabled")] = false
            p[stringPreferencesKey("reasoning_format")] = "THINKING_TYPE"
            p[stringPreferencesKey("system_prompt")] = "prompt"
            p[booleanPreferencesKey("library_tools")] = true
        }
        val migrated = repo.settings.first()
        assertEquals("https://api.deepseek.com", migrated.baseUrl)
        assertEquals("fixture-key", migrated.apiKey)
        assertEquals("deepseek-flash", migrated.model)
        assertTrue(migrated.supportsImages)
        assertFalse(migrated.thinkingEnabled)
        assertEquals(ApiFormat.THINKING_TYPE, migrated.apiFormat)
        assertEquals("DeepSeek", migrated.connectionName)
        assertEquals("prompt", migrated.systemPrompt)
        assertTrue(migrated.libraryToolsEnabled)

        repo.saveConnection(ApiConnection(id = "second", name = "Qwen", baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey = "second-key", model = "qwen-plus"))
        val list = repo.connections.first()
        assertEquals(listOf(LEGACY_CONNECTION_ID, "second"), list.items.map { it.id })
        assertEquals(LEGACY_CONNECTION_ID, list.active.id)
        assertEquals("fixture-key", list.items.first().apiKey)
        val raw = store.data.first()
        for (legacy in listOf("base_url", "api_key", "model", "reasoning_format")) assertNull(raw[stringPreferencesKey(legacy)])
        assertNull(raw[booleanPreferencesKey("supports_images")])
        assertEquals("prompt", raw[stringPreferencesKey("system_prompt")])
    }

    @Test fun switchingChangesWhatRequestsUseAndSolvingSettingsStayShared() = storeTest { repo, _ ->
        repo.saveSolving("shared prompt", libraryToolsEnabled = true)
        repo.saveConnection(ApiConnection(id = "vision", name = "Фото", apiKey = "a", model = "vision-model", supportsImages = true), activate = true)
        repo.saveConnection(ApiConnection(id = "text", name = "Текст", apiKey = "b", model = "text-model"))
        assertEquals("vision-model", repo.settings.first().model)
        repo.selectConnection("text")
        val text = repo.settings.first()
        assertEquals("text-model", text.model)
        assertEquals("b", text.apiKey)
        assertFalse(text.supportsImages)
        assertEquals("Текст", text.connectionName)
        assertEquals("shared prompt", text.systemPrompt)
        assertTrue(text.libraryToolsEnabled)
        repo.selectConnection("missing")
        assertEquals("text", repo.connections.first().active.id)
    }

    @Test fun deletingActiveConnectionFallsBackAndLastOneStays() = storeTest { repo, _ ->
        repo.saveConnection(ApiConnection(id = "a", model = "a"), activate = true)
        repo.saveConnection(ApiConnection(id = "b", model = "b"), activate = true)
        repo.deleteConnection(LEGACY_CONNECTION_ID)
        repo.deleteConnection("b")
        assertEquals("a", repo.connections.first().active.id)
        repo.deleteConnection("a")
        assertEquals(listOf("a"), repo.connections.first().items.map { it.id })
    }

    @Test fun similarTasksAreOfferedByDefaultAndKeptUnlessSaved() = storeTest { repo, _ ->
        assertTrue(repo.settings.first().similarTasksEnabled)
        repo.saveSolving("prompt", libraryToolsEnabled = false, similarTasksEnabled = false)
        assertFalse(repo.settings.first().similarTasksEnabled)
        repo.saveSolving("prompt 2", libraryToolsEnabled = true)
        assertFalse(repo.settings.first().similarTasksEnabled)
    }

    @Test fun quickHideConcealsAssistantUntilCodeIsTypedAgain() = storeTest { repo, _ ->
        repo.concealAssistant()
        assertEquals(AssistantLock(), repo.assistantLock.first())
        repo.setAssistantCode("42")
        assertTrue(repo.assistantLock.first().revealed)
        repo.concealAssistant()
        assertEquals(AssistantLock(code = "42", revealed = false), repo.assistantLock.first())
        repo.toggleAssistantRevealed()
        assertTrue(repo.assistantLock.first().revealed)
    }

    @Test fun savedListToleratesUnknownFieldsAndFormatsAndRejectsDamage() {
        val decoded = decodeConnections("""[{"id":"x","model":"m","apiFormat":"FUTURE","futureField":1}]""")!!.single()
        assertEquals(ApiFormat.AUTO, decoded.apiFormat)
        assertEquals("m", decoded.model)
        assertNull(decodeConnections("not json"))
        assertNull(decodeConnections("[]"))
        val items = listOf(ApiConnection(id = "1", name = "Один", apiKey = "k", apiFormat = ApiFormat.ENABLE_THINKING, supportsImages = true))
        assertEquals(items, decodeConnections(encodeConnections(items)))
        assertEquals("1", ConnectionList(items, "deleted").active.id)
    }
}
