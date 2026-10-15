package com.smartcalc.app.ui.scheme

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.smartcalc.app.ui.components.ReadingDialog
import com.caverock.androidsvg.SVG

/** Схема рисуется на «листе»: модель чертит чёрным по прозрачному, и в тёмной теме линии бы пропали. */
private val PAPER = Color(0xFFFFFFFF)

private const val MAX_ZOOM = 8f

/** Разобранная схема и её пропорции. */
private class Scheme(val svg: SVG, val aspect: Float) {
    companion object {
        /** null — модель прислала то, что не разбирается как SVG. */
        fun parse(source: String): Scheme? = try {
            // Внутренние сущности XML схеме не нужны, а раздуть документ ими можно до бесконечности.
            SVG.setInternalEntitiesEnabled(false)
            val svg = SVG.getFromString(source)
            // Без viewBox непонятно, как вписать рисунок в лист: берём размеры документа или 400×300.
            val box = svg.documentViewBox ?: run {
                val w = svg.documentWidth.takeIf { it > 0 } ?: 400f
                val h = svg.documentHeight.takeIf { it > 0 } ?: 300f
                svg.setDocumentViewBox(0f, 0f, w, h)
                svg.documentViewBox
            }
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            if (box == null || box.width() <= 0 || box.height() <= 0) null
            else Scheme(svg, (box.width() / box.height()).coerceIn(0.5f, 3f))
        } catch (e: Exception) {
            null
        }
    }
}

/** Схема или чертёж из ответа модели; нажатие открывает её на весь экран с масштабом пальцами. */
@Composable
fun SchemeView(source: String, modifier: Modifier = Modifier) {
    val scheme = remember(source) { Scheme.parse(source) }
    var fullscreen by remember { mutableStateOf(false) }
    if (scheme == null) {
        Text(
            "Не удалось нарисовать схему",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(scheme.aspect)
            .clip(RoundedCornerShape(16.dp))
            .background(PAPER)
            .clickable { fullscreen = true },
    ) {
        SchemeCanvas(scheme, Modifier.fillMaxSize().padding(8.dp))
        Icon(
            Icons.Filled.OpenInFull,
            contentDescription = "Открыть схему на весь экран",
            tint = Color.Black.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(18.dp),
        )
    }
    if (fullscreen) SchemeFullscreen(scheme, onDismiss = { fullscreen = false })
}

@Composable
private fun SchemeCanvas(scheme: Scheme, modifier: Modifier) {
    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            scheme.svg.renderToCanvas(canvas.nativeCanvas, RectF(0f, 0f, size.width, size.height))
        }
    }
}

/** Весь экран: щипок — масштаб, палец — сдвиг, двойное касание — приблизить или вернуть как было. */
@Composable
private fun SchemeFullscreen(scheme: Scheme, onDismiss: () -> Unit) {
    ReadingDialog("Схема", onBack = onDismiss) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var area by remember { mutableStateOf(IntSize.Zero) }

        // Сдвиг ограничен так, чтобы увеличенная схема не уезжала за край целиком.
        fun clamp(o: Offset, s: Float) = Offset(
            o.x.coerceIn(-area.width * (s - 1) / 2, area.width * (s - 1) / 2),
            o.y.coerceIn(-area.height * (s - 1) / 2, area.height * (s - 1) / 2),
        )

        val state = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
            offset = clamp(offset + pan, scale)
        }
        Box(
            modifier = Modifier
                .weight(1f).fillMaxWidth()
                .background(Color.Black)
                .clipToBounds()
                .onSizeChanged { area = it }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                // Приближаем то место, куда коснулись.
                                val center = Offset(area.width / 2f, area.height / 2f)
                                offset = clamp((center - tap) * (scale - 1), scale)
                            }
                        },
                    )
                }
                .transformable(state),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(scheme.aspect)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .background(PAPER),
            ) {
                SchemeCanvas(scheme, Modifier.fillMaxSize().padding(8.dp))
            }

        }
    }
}
