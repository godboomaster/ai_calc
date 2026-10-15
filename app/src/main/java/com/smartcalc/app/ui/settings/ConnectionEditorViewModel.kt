package com.smartcalc.app.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.smartcalc.app.data.settings.ApiConnection
import com.smartcalc.app.data.settings.ApiFormat
import com.smartcalc.app.data.settings.ApiSettings
import com.smartcalc.app.data.settings.ConnectionList
import java.util.UUID

/** One navigation entry owns one draft. Credentials never enter saved instance state. */
class ConnectionEditorViewModel(connectionId: String?) : ViewModel() {
    val id = connectionId ?: UUID.randomUUID().toString()
    var loaded by mutableStateOf(false)
        private set
    var name by mutableStateOf("")
    var baseUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var model by mutableStateOf("")
    var apiFormat by mutableStateOf(ApiFormat.AUTO)
    var supportsImages by mutableStateOf(false)
    var thinkingEnabled by mutableStateOf(true)

    fun loadOnce(list: ConnectionList) {
        if (loaded) return
        list.items.firstOrNull { it.id == id }?.let { saved ->
            name = saved.name
            baseUrl = saved.baseUrl
            apiKey = saved.apiKey
            model = saved.model
            apiFormat = saved.apiFormat
            supportsImages = saved.supportsImages
            thinkingEnabled = saved.thinkingEnabled
        }
        loaded = true
    }

    /** Normalization is for explicit save/test/copy; it does not mutate the editor. */
    fun toConnection() = ApiConnection(
        id = id,
        name = name.trim(),
        baseUrl = baseUrl.trim().ifBlank { ApiSettings.DEFAULT_BASE_URL },
        apiKey = apiKey.trim(),
        model = model.trim().ifBlank { ApiSettings.DEFAULT_MODEL },
        apiFormat = apiFormat,
        supportsImages = supportsImages,
        thinkingEnabled = thinkingEnabled,
    )

    override fun onCleared() {
        apiKey = ""
    }

    companion object {
        fun factory(connectionId: String?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ConnectionEditorViewModel::class.java))
                return ConnectionEditorViewModel(connectionId) as T
            }
        }
    }
}
