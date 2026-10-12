package com.smartcalc.app.data.library.importer

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object PublicationIdentity {
    private val json = Json { encodeDefaults = true }
    fun descriptor(intent: PublicationIntent) = digest(json.encodeToString(intent).toByteArray())
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun file(file: File): String {
        require(file.isFile && file.length() <= 15L * 1024 * 1024) { "Фото недоступно или больше 15 МиБ" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val buffer = ByteArray(8192); var total = 0L
            while (true) { val n = stream.read(buffer); if (n < 0) break; total += n; require(total <= 15L * 1024 * 1024); digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun media(intent: PublicationIntent): Map<String, String> = intent.items.flatMap { it.conditionImages + it.solutionImages }
        .distinct().associateWith { file(File(it)) }
    fun aggregate(descriptor: String, media: Map<String, String>) = digest((descriptor + json.encodeToString(media.toSortedMap().toMap())).toByteArray())
    fun verify(receipt: ImportReceipt, intent: PublicationIntent) {
        check(receipt.descriptorSha256 == descriptor(intent)) { "Состав уже опубликованного импорта изменился" }
        receipt.mediaSha256.forEach { (path, expected) ->
            if (File(path).isFile) check(file(File(path)) == expected) { "Материал опубликованного импорта изменился" }
        }
    }
}
