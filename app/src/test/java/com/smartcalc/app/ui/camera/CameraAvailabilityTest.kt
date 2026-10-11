package com.smartcalc.app.ui.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CameraAvailabilityTest {
    @Test fun frontCameraIsUsedWhenBackCameraIsMissing() {
        assertEquals(CameraFacing.FRONT, preferredCamera(false, true))
        assertEquals(CameraFacing.BACK, preferredCamera(true, true))
    }

    @Test fun noCameraReturnsUnavailableInsteadOfBindingAMissingLens() {
        assertNull(preferredCamera(false, false))
    }

    @Test fun initializationFailureBecomesRecoverableUiState() = runBlocking {
        val error = IllegalStateException("No camera provider")
        assertSame(error, initializeCamera<Unit> { throw error }.exceptionOrNull())
        assertEquals("camera", initializeCamera { "camera" }.getOrNull())
    }

    @Test fun leavingScreenStillCancelsCameraStartup() {
        assertThrows(CancellationException::class.java) {
            runBlocking { initializeCamera<Unit> { throw CancellationException("screen closed") } }
        }
    }
}
