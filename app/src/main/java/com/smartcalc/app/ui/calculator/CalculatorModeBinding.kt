package com.smartcalc.app.ui.calculator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.smartcalc.app.SolutionViewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class CalculatorModeBinding(
    val state: CalculatorModes,
    val presentation: CalculatorPresentation,
    val field: TextFieldState,
    val dispatch: (CalculatorEvent) -> Unit,
)

// Configuration/navigation restore the editor. A new process is handled by AppRoot's session key
// and CalculatorSession, which restore drafts without reopening the assistant or keyboard.
private val modesSaver = Saver<CalculatorModes, String>(
    save = { Json.encodeToString(it.copy(imeShown = false)) },
    restore = { Json.decodeFromString<CalculatorModes>(it) },
)

/** Executes model commands and forwards Android/Compose events; it contains no transition policy. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun rememberCalculatorModes(
    vm: SolutionViewModel,
    context: CalculatorContext,
    solutionId: String?,
    onRestoreAssistant: () -> Unit,
): CalculatorModeBinding {
    val shown by vm.shown.collectAsState()
    var state by rememberSaveable(stateSaver = modesSaver) {
        mutableStateOf(CalculatorModes.fromSession(vm.session.value.calculator, solutionId, shown))
    }
    var field by rememberSaveable(stateSaver = TextFieldState.Saver) { mutableStateOf(TextFieldState()) }
    val keyboard = LocalSoftwareKeyboardController.current

    fun dispatch(event: CalculatorEvent) {
        val transition = state.reduce(event, context, field.text.toString())
        state = transition.state
        transition.effects.forEach { effect ->
            when (effect) {
                CalculatorEffect.HideAssistant -> vm.hideFromDisplay()
                CalculatorEffect.ShowAssistant -> vm.showOnDisplay()
                CalculatorEffect.RestoreAssistant -> onRestoreAssistant()
                CalculatorEffect.HideKeyboard -> keyboard?.hide()
                CalculatorEffect.ToggleAssistant -> vm.toggleAssistant()
                is CalculatorEffect.EditField -> field = TextFieldState(effect.text, TextRange(effect.text.length))
                is CalculatorEffect.Solve -> vm.solveText(effect.text)
                is CalculatorEffect.AskFollowUp -> vm.askFollowUp(effect.text)
            }
        }
    }

    LaunchedEffect(Unit) {
        // Also save an open editor: force-stop is not required to invoke lifecycle callbacks.
        snapshotFlow { state.session(field.text.toString()) }.collect(vm::saveCalculator)
    }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(context.assistantVisible, context.assistantUnread, context.solving, state.editing, state.aiKeys, lifecycle) {
        if (state.shouldMarkViewed(context, lifecycle == Lifecycle.State.RESUMED)) vm.markViewed()
    }
    LaunchedEffect(shown) { dispatch(CalculatorEvent.Shown(shown)) }
    LaunchedEffect(solutionId) { dispatch(CalculatorEvent.SolutionChanged(solutionId)) }
    val editRequest = vm.editRequest
    LaunchedEffect(editRequest) {
        if (editRequest != null) {
            vm.editRequest = null
            dispatch(CalculatorEvent.EditRequested(editRequest.text, editRequest.followUp))
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible, state.editing) { dispatch(CalculatorEvent.KeyboardVisibility(imeVisible)) }
    BackHandler(enabled = state.editing) { dispatch(CalculatorEvent.CloseEditor) }
    BackHandler(enabled = state.aiKeys && !state.editing) { dispatch(CalculatorEvent.DismissKeys) }
    LaunchedEffect(context.concealed) {
        if (context.concealed) dispatch(CalculatorEvent.Conceal)
    }
    val visible = state.visibleIn(context)
    return CalculatorModeBinding(visible, visible.presentation(context), field, ::dispatch)
}
