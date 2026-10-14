package com.smartcalc.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

@Composable
internal fun ReadableImage(path: String, description: String, modifier: Modifier = Modifier) {
    var expanded by remember(path) { mutableStateOf(false) }
    Column(modifier) {
        AsyncImage(File(path), description, contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "Увеличить изображение") { expanded = true })
        TextButton(onClick = { expanded = true }) { Text("Увеличить") }
    }
    if (expanded) ReadingDialog(description, onBack = { expanded = false }) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        fun resize(newScale: Float, pan: Offset = Offset.Zero) {
            scale = newScale.coerceIn(1f, 5f)
            val maxX = size.width * (scale - 1f) / 2f
            val maxY = size.height * (scale - 1f) / 2f
            offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
        }
        val transform = rememberTransformableState { zoom, pan, _ -> resize(scale * zoom, pan) }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { size = it }
            .transformable(transform), contentAlignment = Alignment.Center) {
            AsyncImage(File(path), description, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                })
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { resize(scale - 0.5f) }, enabled = scale > 1f) { Text("−") }
            TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("${(scale * 100).toInt()}% · Сброс") }
            OutlinedButton(onClick = { resize(scale + 0.5f) }, enabled = scale < 5f) { Text("+") }
        }
    }
}
