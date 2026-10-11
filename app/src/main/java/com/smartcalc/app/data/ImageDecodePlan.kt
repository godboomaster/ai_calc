package com.smartcalc.app.data

/** Power-of-two decode sampling, before a bitmap is allocated. */
internal fun imageDecodeSampleSize(width: Int, height: Int, maxSide: Int): Int {
    require(maxSide > 0)
    if (width <= 0 || height <= 0 || width > 50_000 || height > 50_000 ||
        width.toLong() * height > 150_000_000L) {
        throw DocumentException("Изображение слишком большое или повреждено. Выберите другое фото.")
    }
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
    return sample
}
