package com.smartcalc.app.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.smartcalc.app.SolutionViewModel
import com.smartcalc.app.data.ImageUtils
import com.smartcalc.app.data.library.source.SourceInput
import android.provider.OpenableColumns
import com.smartcalc.app.data.format.SolutionFormat
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun CameraScreen(
    vm: SolutionViewModel,
    onBack: () -> Unit,
    onOpenFormat: () -> Unit,
    onSolved: () -> Unit,
    onImportInput: (suspend (SourceInput) -> Unit)? = null,
    onImported: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val format by vm.format.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraAttempt by remember { mutableIntStateOf(0) }
    var flashOn by remember { mutableStateOf(false) }
    // Пока фото снимается и сжимается, второй снимок не нужен.
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        onDispose { cameraProvider?.unbindAll() }
    }

    LaunchedEffect(hasPermission, lifecycleOwner, cameraAttempt) {
        camera = null
        cameraError = null
        if (!hasPermission) return@LaunchedEffect
        val result = initializeCamera {
            val provider = context.getCameraProvider()
            cameraProvider = provider
            val selector = when (preferredCamera(
                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA),
                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA),
            )) {
                CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                null -> throw IllegalStateException("No available camera")
            }
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        }
        result.onSuccess { camera = it }.onFailure {
            cameraError = "Камера недоступна. Выберите фото из галереи или попробуйте ещё раз."
        }
    }

    /** Решение идёт в фоне, а ответ появится на дисплее калькулятора. */
    fun solveBytes(jpeg: ByteArray) {
        vm.solveImage(jpeg)
        onSolved()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    if (onImportInput != null) {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Фото.jpg"
                        onImportInput(SourceInput(name, context.contentResolver.getType(uri)) { checkNotNull(context.contentResolver.openInputStream(uri)) })
                        onImported()
                    } else solveBytes(ImageUtils.compressUri(context, uri))
                } catch (e: Exception) {
                    Toast.makeText(context, "Не удалось открыть изображение", Toast.LENGTH_SHORT).show()
                    busy = false
                }
            }
        }
    }

    fun capture() {
        if (busy || camera == null) return
        busy = true
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    scope.launch {
                        try {
                            if (onImportInput != null) {
                                withContext(NonCancellable) { onImportInput(SourceInput(file.name, "image/jpeg") { file.inputStream() }) }
                                onImported()
                            } else solveBytes(ImageUtils.compressFile(file.absolutePath))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Не удалось обработать фото", Toast.LENGTH_SHORT).show()
                            busy = false
                        } finally {
                            file.delete()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(context, "Ошибка съёмки", Toast.LENGTH_SHORT).show()
                    busy = false
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission && cameraError == null) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            ScanFrameOverlay(Modifier.align(Alignment.Center))
            Text(
                if (camera == null) "Подключение камеры…" else "Наведите камеру на задачу",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 260.dp)
                    .background(Color(0x66000000), CircleShape)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(cameraError ?: "Для сканирования задач нужен доступ к камере", color = Color.White)
                Button(
                    onClick = {
                        if (hasPermission) cameraAttempt++ else permissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(if (hasPermission) "Повторить" else "Разрешить") }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = Color.White,
                    )
                }
                Text(
                    "Сфотографировать",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = !busy && camera?.cameraInfo?.hasFlashUnit() == true, onClick = {
                    flashOn = !flashOn
                    imageCapture.flashMode =
                        if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                }) {
                    Icon(
                        if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = "Вспышка",
                        tint = Color.White,
                    )
                }
            }
            if (onImportInput == null) FormatChip(
                format = format,
                onClick = onOpenFormat,
                onClear = { vm.clearFormat() },
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                enabled = !busy,
            ) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = "Галерея",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(Color.White, CircleShape)
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { capture() }, enabled = !busy && camera != null, modifier = Modifier.fillMaxSize()) {
                    if (busy) {
                        CircularProgressIndicator()
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                }
            }
            Box(modifier = Modifier.size(32.dp))
        }
    }
}

/** Плашка под верхней панелью: показывает, задан ли формат решения, и открывает его настройку. */
@Composable
private fun FormatChip(
    format: SolutionFormat,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (format.isSet) MaterialTheme.colorScheme.primary else Color(0x66000000))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = if (format.isSet) 6.dp else 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentColor = if (format.isSet) MaterialTheme.colorScheme.onPrimary else Color.White
        Icon(
            Icons.Filled.Tune,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            if (format.isSet) "Оформление: ${format.summary}" else "Настроить оформление",
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 6.dp)
                .widthIn(max = 240.dp),
        )
        if (format.isSet) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Сбросить формат",
                tint = contentColor,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear)
                    .padding(4.dp)
                    .size(16.dp),
            )
        }
    }
}

@Composable
private fun ScanFrameOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 300.dp, height = 200.dp)) {
        val strokeWidth = 5.dp.toPx()
        val corner = 32.dp.toPx()
        val radius = CornerRadius(14.dp.toPx())
        val color = Color.White
        val style = Stroke(width = strokeWidth)

        drawRoundRect(
            color = color.copy(alpha = 0.25f),
            size = size,
            cornerRadius = radius,
            style = Stroke(width = 1.dp.toPx()),
        )
        // верхний левый
        drawLine(color, Offset(0f, corner), Offset(0f, 0f), strokeWidth)
        drawLine(color, Offset(0f, 0f), Offset(corner, 0f), strokeWidth)
        // верхний правый
        drawLine(color, Offset(size.width - corner, 0f), Offset(size.width, 0f), strokeWidth)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, corner), strokeWidth)
        // нижний левый
        drawLine(color, Offset(0f, size.height - corner), Offset(0f, size.height), strokeWidth)
        drawLine(color, Offset(0f, size.height), Offset(corner, size.height), strokeWidth)
        // нижний правый
        drawLine(color, Offset(size.width - corner, size.height), Offset(size.width, size.height), strokeWidth)
        drawLine(color, Offset(size.width, size.height - corner), Offset(size.width, size.height), strokeWidth)
    }
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (cont.isActive) {
                    try { cont.resume(future.get()) }
                    catch (error: Exception) { cont.resumeWithException(error.cause ?: error) }
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
