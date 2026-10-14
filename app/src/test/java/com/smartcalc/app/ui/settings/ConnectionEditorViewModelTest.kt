package com.smartcalc.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.smartcalc.app.data.settings.ApiConnection
import com.smartcalc.app.data.settings.ApiFormat
import com.smartcalc.app.data.settings.ConnectionList
import org.junit.Assert.*
import org.junit.Test

class ConnectionEditorViewModelTest {
    private val original = ApiConnection(id = "A", name = "Original", model = "model-A", apiKey = "synthetic-original")

    @Test fun originalLoadsOnceAndListRefreshNeverOverwritesDraft() {
        val editor = ConnectionEditorViewModel("A")
        assertFalse(editor.loaded)
        editor.loadOnce(ConnectionList(listOf(original), "A"))
        editor.name = "Edited"
        editor.apiKey = "synthetic-unsaved"
        editor.model = "edited-model"
        editor.loadOnce(ConnectionList(listOf(original.copy(name = "External", apiKey = "synthetic-replacement")), "A"))
        assertEquals("Edited", editor.name)
        assertEquals("edited-model", editor.model)
        assertTrue("Unsaved key must remain in memory", editor.apiKey == "synthetic-unsaved")
    }

    @Test fun navigationStoreRetainsDraftThroughRecreationAndSeparateEntriesStayIndependent() {
        val aStore = ViewModelStore()
        val bStore = ViewModelStore()
        try {
            val a = obtain(aStore, "A")
            a.loadOnce(ConnectionList(listOf(original), "A"))
            a.name = "Draft A"; a.apiKey = "synthetic-unsaved"
            val recreated = obtain(aStore, "A")
            assertSame(a, recreated)
            assertEquals("Draft A", recreated.name)
            assertTrue(recreated.apiKey == "synthetic-unsaved")
            val b = obtain(bStore, "B")
            b.loadOnce(ConnectionList(listOf(original, original.copy(id = "B", name = "Original B")), "A"))
            assertNotSame(a, b)
            assertEquals("Original B", b.name)
            b.name = "Draft B"
            assertEquals("Draft A", a.name)
        } finally { aStore.clear(); bStore.clear() }
    }

    @Test fun leavingScreenClearsKeyAndFreshScreenStartsFromSavedSettings() {
        val store = ViewModelStore()
        val a = obtain(store, "A")
        a.loadOnce(ConnectionList(listOf(original), "A"))
        a.apiKey = "synthetic-unsaved"
        store.clear()
        assertTrue("Disposed draft must release credentials", a.apiKey.isEmpty())
        val fresh = obtain(store, "A")
        fresh.loadOnce(ConnectionList(listOf(original), "A"))
        assertNotSame(a, fresh)
        assertTrue(fresh.apiKey == original.apiKey)
        store.clear()
    }

    @Test fun newDraftIdAndAllFieldsRemainStableAndNormalizeOnlyOnExplicitSave() {
        val editor = ConnectionEditorViewModel(null)
        editor.loadOnce(ConnectionList(listOf(original), "A"))
        val id = editor.id
        editor.name = " Name "; editor.baseUrl = " https://fixture.example/v1 "
        editor.apiKey = " synthetic-unsaved "; editor.model = " fixture-model "
        editor.apiFormat = ApiFormat.ENABLE_THINKING
        editor.supportsImages = true; editor.thinkingEnabled = false
        editor.loadOnce(ConnectionList(listOf(original.copy(name = "Changed")), "A"))
        assertEquals(id, editor.id)
        val saved = editor.toConnection()
        assertEquals(id, saved.id); assertEquals("Name", saved.name)
        assertEquals("https://fixture.example/v1", saved.baseUrl); assertEquals("fixture-model", saved.model)
        assertTrue(saved.apiKey == "synthetic-unsaved")
        assertEquals(ApiFormat.ENABLE_THINKING, saved.apiFormat)
        assertTrue(saved.supportsImages); assertFalse(saved.thinkingEnabled)
        assertTrue("Normalization must not mutate the draft", editor.apiKey == " synthetic-unsaved ")
    }

    private fun obtain(store: ViewModelStore, id: String): ConnectionEditorViewModel =
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ConnectionEditorViewModel(id) as T
        })[ConnectionEditorViewModel::class.java]
}
