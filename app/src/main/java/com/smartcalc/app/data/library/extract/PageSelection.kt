package com.smartcalc.app.data.library.extract

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

@Serializable data class PageSelection(val ordinals: List<Int>) {
    init { require(ordinals.isNotEmpty() && ordinals.size <= 100 && ordinals.all { it > 0 } && ordinals.zipWithNext().all { (a, b) -> a < b }) { "Выберите от 1 до 100 страниц по порядку" } }
}
internal fun rasterDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "Повреждённые размеры страницы" }
    val scale = min(1400.0 / maxOf(width, height), sqrt(4_000_000.0 / (width.toDouble() * height)))
    val w = floor(width * scale).toInt().coerceAtLeast(1)
    val h = floor(height * scale).toInt().coerceAtLeast(1)
    return w to h
}
/** Unselected pages need not allocate a list proportional to a hostile page count. */
internal fun remainingPages(count: Int, selected: List<Int>): List<Int> = object : AbstractList<Int>() {
    override val size = count - selected.size
    override fun get(index: Int): Int {
        require(index in 0 until size)
        var value = index + 1
        selected.forEach { if (it <= value) value++ }
        return value
    }
}
