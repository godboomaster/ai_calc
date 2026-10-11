package com.smartcalc.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Visibility is a user choice; finishing a request must never change it. */
internal class AssistantDisplay {
    data class State(val visible: Boolean = false, val unread: Boolean = false)

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun start() { mutableState.value = State(visible = true) }
    fun hide() { mutableState.update { it.copy(visible = false) } }
    fun show() { mutableState.update { it.copy(visible = true) } }
    fun complete() { mutableState.update { it.copy(unread = true) } }

    // Called only after the result/error is rendered on a resumed assistant screen.
    fun markViewed() {
        mutableState.update { if (it.visible) it.copy(unread = false) else it }
    }
}
