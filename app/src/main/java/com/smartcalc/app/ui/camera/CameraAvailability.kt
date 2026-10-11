package com.smartcalc.app.ui.camera

import kotlinx.coroutines.CancellationException

internal enum class CameraFacing { BACK, FRONT }

internal fun preferredCamera(backAvailable: Boolean, frontAvailable: Boolean): CameraFacing? = when {
    backAvailable -> CameraFacing.BACK
    frontAvailable -> CameraFacing.FRONT
    else -> null
}

internal suspend fun <T> initializeCamera(open: suspend () -> T): Result<T> = try {
    Result.success(open())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
