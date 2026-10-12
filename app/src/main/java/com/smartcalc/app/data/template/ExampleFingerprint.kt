package com.smartcalc.app.data.template

import java.io.File
import java.security.MessageDigest

/** Identifies a content snapshot, independently of its position or copied image paths. */
internal object ExampleFingerprint {
    fun key(example: TemplateExample): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
            digest.update(bytes)
        }
        fun images(paths: List<String>) {
            field(paths.size.toString())
            paths.forEach { path ->
                val image = MessageDigest.getInstance("SHA-256")
                val hash = runCatching {
                    File(path).inputStream().buffered().use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            image.update(buffer, 0, read)
                        }
                    }
                    image.digest().hex()
                }.getOrElse {
                    // Missing media must not collapse unrelated photo-only examples into one hit.
                    "unavailable:${File(path).absolutePath}"
                }
                field(hash)
            }
        }
        field(example.condition.trim().replace(Regex("\\s+"), " "))
        images(example.conditionImages)
        field(example.solution.trim().replace(Regex("\\s+"), " "))
        images(example.solutionImages)
        if (example.answer.isNotBlank()) { field("answer-v1"); field(example.answer.trim().replace(Regex("\\s+"), " ")) }
        return "${if (example.answer.isBlank()) "content-v1" else "content-v2"}:${digest.digest().hex()}"
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
