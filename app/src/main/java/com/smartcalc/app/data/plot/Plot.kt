package com.smartcalc.app.data.plot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * График, который присылает модель: не картинка и не точки, а формулы и диапазоны —
 * точки приложение считает само (см. [PlotBuilder]). Числа хранятся строками: модель
 * пишет и «0.5», и «2*pi», и то и другое вычисляет [FormulaParser].
 */
@Serializable
data class Plot(
    val title: String? = null,
    val x: PlotAxis = PlotAxis(),
    val y: PlotAxis = PlotAxis(),
    /** Числа для буквенных параметров задачи: {"k": "2", "T": "0.5"}. */
    val params: Map<String, String> = emptyMap(),
    val series: List<PlotSeries> = emptyList(),
)

@Serializable
data class PlotAxis(
    val label: String? = null,
    /** Имя переменной в формулах кривых y(x); по умолчанию «x». */
    @SerialName("var") val variable: String? = null,
    val from: String? = null,
    val to: String? = null,
    val log: Boolean = false,
)

/**
 * Кривая: y(x) — задано только [y]; параметрическая (x(t), y(t), например годограф) — заданы
 * [x] и [y], а [variable], [from], [to] и [log] описывают параметр; таблица — [points].
 */
@Serializable
data class PlotSeries(
    val label: String? = null,
    val x: String? = null,
    val y: String? = null,
    @SerialName("var") val variable: String? = null,
    val from: String? = null,
    val to: String? = null,
    val log: Boolean = false,
    val points: List<List<Double>>? = null,
)
