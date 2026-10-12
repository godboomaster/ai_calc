package com.smartcalc.app.data.library.extract

import org.w3c.dom.Element

object OmmlText {
    fun convert(xml: String): OmmlResult = convert(SafeXml.parse(xml))
    internal fun convert(root: Element): OmmlResult {
        val warnings = mutableListOf<ExtractionWarning>()
        fun render(node: Element): String {
            fun children() = node.children().joinToString("") { render(it) }
            fun child(name: String) = node.children().firstOrNull { it.localName == name }?.let(::render).orEmpty()
            return when (node.localName) {
                "t" -> node.textContent
                "oMath", "oMathPara", "r", "num", "den", "e", "sup", "sub", "deg" -> children()
                "f" -> "\\frac{${child("num")}}{${child("den")}}"
                "sSup" -> "{${child("e")}}^{${child("sup")}}"
                "sSub" -> "{${child("e")}}_{${child("sub")}}"
                "sSubSup" -> "{${child("e")}}_{${child("sub")}}^{${child("sup")}}"
                "rad" -> child("deg").let { degree -> if (degree.isEmpty()) "\\sqrt{${child("e")}}" else "\\sqrt[$degree]{${child("e")}}" }
                "rPr", "ctrlPr", "fPr", "sSupPr", "sSubPr", "sSubSupPr", "radPr", "degHide" -> ""
                else -> {
                    warnings += ExtractionWarning("omml_unsupported", "Формула Word содержит неподдержанный элемент ${node.localName}. Сверьте с оригиналом.")
                    children()
                }
            }
        }
        return OmmlResult(render(root), warnings.distinct())
    }
}
