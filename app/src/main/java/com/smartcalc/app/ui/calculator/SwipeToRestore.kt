package com.smartcalc.app.ui.calculator

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Only an intentional upward swipe restores the assistant; taps and other directions remain free. */
@Composable
internal fun Modifier.swipeUpToRestore(enabled: Boolean, onRestore: () -> Unit): Modifier {
    val currentOnRestore by rememberUpdatedState(onRestore)
    if (!enabled) return this
    return pointerInput(Unit) {
        val minimumDistance = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var claimed = false
            var cancelled = false
            while (true) {
                // Intercept a confirmed upward drag before the calculator expression's scroll handler.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.any { it.id != down.id && it.pressed }) cancelled = true
                val distance = change.position - down.position
                if (!claimed && distance.getDistance() > viewConfiguration.touchSlop) {
                    if (cancelled || distance.y >= 0f || -distance.y <= abs(distance.x) * 1.5f) break
                    // Short drags may still scroll a long expression; claim only at restore distance.
                    claimed = -distance.y >= minimumDistance
                }
                if (claimed) change.consume()
                if (!change.pressed) {
                    if (claimed && !cancelled && -distance.y >= minimumDistance &&
                        -distance.y > abs(distance.x) * 1.5f
                    ) {
                        currentOnRestore()
                    }
                    break
                }
            }
        }
    }
}
