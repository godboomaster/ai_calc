package com.smartcalc.app.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.smartcalc.app.MainActivity
import com.smartcalc.app.SmartCalcApplication
import com.smartcalc.app.data.settings.ApiConnection
import com.smartcalc.app.data.settings.ApiFormat
import com.smartcalc.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/** Opt-in isolated APK only: real AppRoot navigation, editor, rotations and repository actions, no HTTP. */
class ConnectionEditorDeviceTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val isolatedApp: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "com.smartcalc.app.connectionscheck") {
                "Use -PconnectionsDeviceCheck; refuse to launch the user's app"
            }
        }
    }).around(ui)
    private val settings: SettingsRepository
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.smartcalc.app.connectionscheck") { "Use -PconnectionsDeviceCheck; never run against the user's app" }
            return (context.applicationContext as SmartCalcApplication).settingsRepository
        }

    private fun openSettings() {
        runBlocking {
            settings.setAssistantCode("")
            settings.saveConnection(ApiConnection(id = "rotation-A", name = "Rotation A", model = "fixture-A",
                baseUrl = "https://fixture.invalid/v1", apiKey = "synthetic-saved-A", apiFormat = ApiFormat.ENABLE_THINKING,
                supportsImages = true), activate = true)
            settings.saveConnection(ApiConnection(id = "rotation-B", name = "Rotation B", model = "fixture-B",
                baseUrl = "https://fixture.invalid/v1", apiKey = "synthetic-saved-B"))
            // This package was created solely for these fixtures; make repeat runs deterministic.
            settings.connections.first().items.filter { it.id !in setOf("rotation-A", "rotation-B") }
                .forEach { settings.deleteConnection(it.id) }
        }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("ИИ-помощник").performClick()
        ui.onNodeWithText("Настройки").performClick()
    }

    @Test fun existingDraftSurvivesLandscapeAndPortraitAndRepositoryActionsStillWork() = safeTest {
        openSettings()
        ui.onNodeWithContentDescription("Изменить подключение «Rotation A»").performScrollTo().performClick()
        replace("Название", "Draft A")
        replace("Base URL", "https://edited.invalid/v1")
        replace("API-ключ", "synthetic-unsaved-A")
        assertField("API-ключ", "synthetic-unsaved-A")
        replace("Модель", "edited-model")
        ui.onNodeWithText("Рассуждение модели").performScrollTo().performClick()
        ui.onNodeWithText("Фото", substring = false).performScrollTo().performClick()
        hideKeyboard()
        assertKeyNotSavedWithActivity("synthetic-unsaved-A")
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertField("Название", "Draft A")
        assertField("Base URL", "https://edited.invalid/v1")
        assertField("API-ключ", "synthetic-unsaved-A")
        assertField("Модель", "edited-model")
        // Refresh both list and active connection while the editor stays open.
        runBlocking {
            val a = settings.connections.first().items.first { it.id == "rotation-A" }
            assertTrue("Unsaved credentials must not be persisted", a.apiKey == "synthetic-saved-A")
            settings.saveConnection(a.copy(name = "External update"))
            settings.selectConnection("rotation-B")
        }
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertField("Название", "Draft A")
        assertField("API-ключ", "synthetic-unsaved-A")
        click("Сохранить")
        ui.waitUntil(10000) { runBlocking { settings.connections.first().items.first { it.id == "rotation-A" }.name == "Draft A" } }
        val saved = runBlocking { settings.connections.first().items.first { it.id == "rotation-A" } }
        assertTrue("Explicit save must store the draft credentials", saved.apiKey == "synthetic-unsaved-A")
        assertEquals("https://edited.invalid/v1", saved.baseUrl)
        assertEquals("edited-model", saved.model)
        assertFalse(saved.supportsImages)
        assertFalse(saved.thinkingEnabled)
        assertEquals(ApiFormat.ENABLE_THINKING, saved.apiFormat)

        click("Копировать")
        ui.waitUntil(10000) { runBlocking { settings.connections.first().items.any { it.name == "Draft A — копия" } } }
        val copy = runBlocking { settings.connections.first().items.first { it.name == "Draft A — копия" } }
        assertTrue("Copy must retain explicitly saved credentials", copy.apiKey == saved.apiKey)
        assertField("Название", "Draft A — копия")
        click("Сделать активным")
        ui.waitUntil(10000) { runBlocking { settings.connections.first().active.id == copy.id } }
        click("Удалить")
        ui.onAllNodesWithText("Удалить").onLast().performClick()
        ui.waitUntil(10000) { runBlocking { settings.connections.first().items.none { it.id == copy.id } } }
        assertTrue(runBlocking { settings.connections.first().active.id != copy.id })
        ui.onNodeWithContentDescription("Изменить подключение «Rotation B»").performScrollTo().performClick()
        assertField("Название", "Rotation B")
        assertField("Модель", "fixture-B")
    }

    @Test fun newDraftSurvivesRotationAndBecomesActiveOnlyAfterSave() = safeTest {
        openSettings()
        click("Добавить подключение")
        replace("Название", "New rotation draft")
        replace("Base URL", "https://127.0.0.1:1/v1") // Closed local port: never contact an external provider.
        replace("API-ключ", "synthetic-new-unsaved")
        assertField("API-ключ", "synthetic-new-unsaved")
        replace("Модель", "new-fixture-model")
        hideKeyboard()
        assertKeyNotSavedWithActivity("synthetic-new-unsaved")
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertField("Название", "New rotation draft")
        assertField("API-ключ", "synthetic-new-unsaved")
        assertField("Модель", "new-fixture-model")
        assertTrue("No implicit save during rotation", runBlocking {
            settings.connections.first().items.none { it.apiKey == "synthetic-new-unsaved" }
        })
        click("Проверить соединение")
        ui.waitUntil(10000) {
            !ui.onNodeWithText("Сохранить").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        assertTrue("Testing a connection must not persist its draft key", runBlocking {
            settings.connections.first().items.none { it.apiKey == "synthetic-new-unsaved" }
        })
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        click("Сохранить")
        ui.waitUntil(10000) { runBlocking { settings.connections.first().active.name == "New rotation draft" } }
        val created = runBlocking { settings.connections.first().active }
        assertTrue(created.apiKey == "synthetic-new-unsaved")
        assertEquals("new-fixture-model", created.model)
        ui.onNodeWithContentDescription("Назад").performClick()
        click("Добавить подключение")
        assertField("Название", "")
        assertField("API-ключ", "")
    }

    private fun field(label: String) = ui.onNode(hasSetTextAction() and hasText(label))
    private fun safeTest(block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            // Compose assertion diagnostics can contain full editable-text semantics, including passwords.
            // Keep a sanitized message and location, without retaining the original exception as a cause.
            val fixtures = listOf("synthetic-saved-A", "synthetic-saved-B", "synthetic-unsaved-A", "synthetic-new-unsaved")
            val message = fixtures.fold(error.message.orEmpty()) { text, secret -> text.replace(secret, "[redacted]") }
            throw AssertionError(message).also { it.stackTrace = error.stackTrace }
        }
    }
    private fun replace(label: String, text: String) { field(label).performScrollTo().performTextReplacement(text) }
    private fun assertField(label: String, expected: String) {
        val actual = field(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        // Boolean assertions prevent credentials appearing as expected/actual values in reports.
        val matches = actual == expected || (label == "API-ключ" && actual == "\u2022".repeat(expected.length))
        assertTrue("Editor field must survive recreation: $label", matches)
    }
    private fun click(text: String) { ui.onNodeWithText(text).performScrollTo().performClick() }

    private fun hideKeyboard() {
        ui.activityRule.scenario.onActivity {
            WindowCompat.getInsetsController(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        ui.waitForIdle()
    }

    private fun rotate(requested: Int, expected: Int) {
        lateinit var before: MainActivity
        ui.activityRule.scenario.onActivity { before = it; it.requestedOrientation = requested }
        ui.waitUntil(15000) {
            var recreated = false
            ui.activityRule.scenario.onActivity {
                recreated = it !== before && it.resources.configuration.orientation == expected
            }
            recreated
        }
        ui.waitForIdle()
    }

    private fun assertKeyNotSavedWithActivity(secret: String) {
        val saved = Bundle()
        ui.activityRule.scenario.onActivity {
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, saved)
        }
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(saved)
            val bytes = parcel.marshall()
            val needle = secret.toByteArray(Charsets.UTF_16LE)
            val contains = (0..bytes.size - needle.size).any { offset -> needle.indices.all { bytes[offset + it] == needle[it] } }
            assertFalse("Unsaved API key must not enter Activity saved state", contains)
        } finally { parcel.recycle() }
    }
}
