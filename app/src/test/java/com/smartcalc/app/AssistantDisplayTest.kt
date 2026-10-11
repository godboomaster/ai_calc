package com.smartcalc.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDisplayTest {
    @Test fun `completion while calculating stays hidden until explicitly restored`() {
        val display = AssistantDisplay()
        display.start()
        display.hide()
        display.complete()
        assertFalse(display.state.value.visible)
        assertTrue(display.state.value.unread)

        display.show()
        assertTrue(display.state.value.visible)
        // Requesting display is not proof it was seen (menu or another screen may be open).
        assertTrue(display.state.value.unread)
        display.markViewed()
        assertFalse(display.state.value.unread)
        display.hide()
        display.show()
        assertFalse(display.state.value.unread)
    }

    @Test fun `hidden error cannot be acknowledged by an unrelated screen`() {
        val display = AssistantDisplay()
        display.start()
        display.hide()
        display.complete() // Both success and error finish through this transition.
        display.markViewed()
        assertTrue(display.state.value.unread)
        assertFalse(display.state.value.visible)
    }

    @Test fun `pending request can be restored and completion stays on the assistant`() {
        val display = AssistantDisplay()
        display.start()
        display.hide()
        display.show()
        assertTrue(display.state.value.visible)
        assertFalse(display.state.value.unread)
        display.complete()
        assertTrue(display.state.value.visible)
        display.markViewed()
        assertFalse(display.state.value.unread)
    }

    @Test fun `new request replaces unread completion without inheriting its marker`() {
        val display = AssistantDisplay()
        display.start()
        display.hide()
        display.complete()
        display.start()
        assertTrue(display.state.value.visible)
        assertFalse(display.state.value.unread)
    }
}
