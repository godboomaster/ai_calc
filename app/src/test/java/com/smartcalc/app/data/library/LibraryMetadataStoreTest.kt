package com.smartcalc.app.data.library

import com.smartcalc.app.data.template.ExampleDraft
import com.smartcalc.app.data.template.ImageDraft
import com.smartcalc.app.data.template.ImportResult
import com.smartcalc.app.data.template.TemplateFormat
import com.smartcalc.app.data.template.TemplateExample
import com.smartcalc.app.data.template.TemplateImportException
import com.smartcalc.app.data.template.TemplateKind
import com.smartcalc.app.data.template.TemplateStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryMetadataStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(root: File = tmp.newFolder()) = TemplateStore(root, TemplateKind.LIBRARY)

    private fun draft(
        condition: String = "Условие",
        solution: String = "Решение",
        conditionPhoto: ByteArray? = null,
        solutionPhoto: ByteArray? = null,
        libraryId: String? = null,
        expectedVersion: Int? = null,
        verification: LibraryVerification? = null,
        enabled: Boolean? = null,
    ) = ExampleDraft(
        condition = condition,
        conditionImages = conditionPhoto?.let { listOf(ImageDraft(bytes = it)) }.orEmpty(),
        solution = solution,
        solutionImages = solutionPhoto?.let { listOf(ImageDraft(bytes = it)) }.orEmpty(),
        libraryId = libraryId,
        expectedVersion = expectedVersion,
        verification = verification,
        enabled = enabled,
    )

    private suspend fun TemplateStore.add(
        condition: String = "Условие",
        solution: String = "Решение",
        conditionPhoto: ByteArray? = null,
        solutionPhoto: ByteArray? = null,
    ) = save(null, "Раздел", "Требования", listOf(draft(condition, solution, conditionPhoto, solutionPhoto)))

    private fun existingDraft(example: TemplateExample) = ExampleDraft(
        condition = example.condition,
        conditionImages = example.conditionImages.map { ImageDraft(path = it) },
        solution = example.solution,
        solutionImages = example.solutionImages.map { ImageDraft(path = it) },
        libraryId = example.library!!.id,
        expectedVersion = example.library!!.version,
    )

    @Test
    fun legacyMigrationIsPersistedBeforeStableIdsAreExposed() {
        val root = tmp.newFolder()
        val file = File(root, "templates.json")
        file.writeText(
            """[{"id":"section","name":"Раздел","examples":[
                {"condition":"A","solution":"1"},{"condition":"B","solution":"2"}]}]""",
        )

        val first = TemplateStore(root, TemplateKind.LIBRARY)
        assertNull(first.loadError.value)
        val ids = first.items.value.single().examples.associate { it.condition to it.library!!.id }
        ids.values.forEach { UUID.fromString(it) }
        assertTrue(file.readText().contains("\"library\""))

        // The persisted UUID follows the example when file order changes and the store restarts.
        val saved = file.readText()
        val examples = first.items.value.single().examples.reversed()
        val reordered = first.items.value.single().copy(examples = examples)
        file.writeText(kotlinx.serialization.json.Json.encodeToString(listOf(reordered)))
        val restarted = TemplateStore(root, TemplateKind.LIBRARY)
        assertEquals(ids, restarted.items.value.single().examples.associate { it.condition to it.library!!.id })
        assertNotEquals(saved, file.readText())
    }

    @Test
    fun textAndPhotoChangesCreateRevisionsAndKeepOldPhotosAlive() = runBlocking {
        val root = tmp.newFolder()
        val target = store(root)
        val oldPhoto = byteArrayOf(1, 2, 3)
        target.add(solutionPhoto = oldPhoto)
        val initial = target.items.value.single().examples.single()
        val id = initial.library!!.id
        val firstPath = initial.solutionImages.single()

        target.save(
            target.items.value.single().id,
            "Раздел",
            "Требования",
            listOf(draft(solutionPhoto = oldPhoto, libraryId = id, expectedVersion = 1)),
            expectedSection = target.items.value.single(),
        )
        val sameBytes = target.items.value.single().examples.single()
        assertEquals(1, sameBytes.library!!.version)
        assertTrue(sameBytes.library!!.previousVersions.isEmpty())
        val oldPath = sameBytes.solutionImages.single()
        assertFalse(firstPath == oldPath)

        target.save(
            target.items.value.single().id,
            " Раздел ",
            " Требования ",
            listOf(
                draft(
                    condition = " Условие ",
                    solution = "Новое решение",
                    solutionPhoto = byteArrayOf(4, 5, 6),
                    libraryId = id,
                    expectedVersion = 1,
                ),
            ),
            expectedSection = target.items.value.single(),
        )

        val changed = target.items.value.single().examples.single()
        assertEquals(2, changed.library!!.version)
        assertEquals(LibraryVerification.UNVERIFIED, changed.library!!.verification)
        val revision = changed.library!!.previousVersions.single()
        assertEquals(1, revision.version)
        assertEquals("Решение", revision.solution)
        assertEquals(listOf(oldPath), revision.solutionImages)
        assertArrayEquals(oldPhoto, File(oldPath).readBytes())
        assertArrayEquals(oldPhoto, TemplateStore(root, TemplateKind.LIBRARY).readImage(oldPath))
    }

    @Test
    fun metadataOnlySaveDoesNotVersionAndEditedContentResetsVerificationUnlessExplicit() = runBlocking {
        val target = store()
        target.add()
        var example = target.items.value.single().examples.single()
        val sectionId = target.items.value.single().id
        val id = example.library!!.id

        target.save(sectionId, "Новое имя", "Новые требования", listOf(draft(libraryId = id, expectedVersion = 1,
            verification = LibraryVerification.VERIFIED_BY_USER, enabled = false)), expectedSection = target.items.value.single())
        example = target.items.value.single().examples.single()
        assertEquals(1, example.library!!.version)
        assertTrue(example.library!!.previousVersions.isEmpty())
        assertEquals(LibraryVerification.VERIFIED_BY_USER, example.library!!.verification)
        assertFalse(example.library!!.enabled)

        target.save(sectionId, "Новое имя", "Новые требования", listOf(draft(solution = "Изменено",
            libraryId = id, expectedVersion = 1, enabled = false)), expectedSection = target.items.value.single())
        example = target.items.value.single().examples.single()
        assertEquals(2, example.library!!.version)
        assertEquals(LibraryVerification.UNVERIFIED, example.library!!.verification)
        assertFalse(example.library!!.enabled)

        target.save(sectionId, "Новое имя", "Новые требования", listOf(draft(solution = "Ещё раз",
            libraryId = id, expectedVersion = 2, verification = LibraryVerification.VERIFIED_BY_USER)),
            expectedSection = target.items.value.single())
        assertEquals(LibraryVerification.VERIFIED_BY_USER,
            target.items.value.single().examples.single().library!!.verification)
    }

    @Test
    fun staleVersionFailsWithoutChangingFilesOrMemory() = runBlocking {
        val root = tmp.newFolder()
        val target = store(root)
        target.add(solutionPhoto = byteArrayOf(1, 2, 3))
        val before = target.items.value
        val metadata = File(root, "templates.json").readText()
        val photos = File(root, "templates").listFiles()!!.map { it.name }.sorted()
        val library = before.single().examples.single().library!!

        val failure = runCatching {
            target.save(before.single().id, "Раздел", "Требования", listOf(draft(
                solution = "Конфликт", solutionPhoto = byteArrayOf(9), libraryId = library.id, expectedVersion = 99,
            )), expectedSection = before.single())
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("верс", ignoreCase = true))
        assertEquals(before, target.items.value)
        assertEquals(metadata, File(root, "templates.json").readText())
        assertEquals(photos, File(root, "templates").listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun staleSectionSnapshotCannotLoseConcurrentAdditionsOrResurrectRemovals() = runBlocking {
        val target = store()
        target.add(condition = "A", solution = "1")
        val staleBeforeAddition = target.items.value.single()

        target.save(
            staleBeforeAddition.id,
            staleBeforeAddition.name,
            staleBeforeAddition.requirements,
            listOf(existingDraft(staleBeforeAddition.examples.single()), draft(condition = "B", solution = "2")),
            expectedSection = staleBeforeAddition,
        )
        val afterAddition = target.items.value.single()
        assertEquals(listOf("A", "B"), afterAddition.examples.map { it.condition })

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                target.save(
                    staleBeforeAddition.id,
                    staleBeforeAddition.name,
                    staleBeforeAddition.requirements,
                    staleBeforeAddition.examples.map(::existingDraft),
                    expectedSection = staleBeforeAddition,
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { target.delete(staleBeforeAddition.id, expectedSection = staleBeforeAddition) }
        }
        assertEquals(afterAddition, target.items.value.single())

        target.save(
            afterAddition.id,
            afterAddition.name,
            afterAddition.requirements,
            listOf(existingDraft(afterAddition.examples.first())),
            expectedSection = afterAddition,
        )
        val afterRemoval = target.items.value.single()
        assertEquals(listOf("A"), afterRemoval.examples.map { it.condition })

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                target.save(
                    afterAddition.id,
                    afterAddition.name,
                    afterAddition.requirements,
                    afterAddition.examples.map(::existingDraft),
                    expectedSection = afterAddition,
                )
            }
        }
        assertEquals(afterRemoval, target.items.value.single())
    }

    @Test
    fun corruptUnsupportedOrFailedMigrationLoadsFailClosedAndGuardMutators() = runBlocking {
        val corruptRoot = tmp.newFolder()
        File(corruptRoot, "templates.json").writeText("{broken")
        val corrupt = TemplateStore(corruptRoot, TemplateKind.LIBRARY)
        assertTrue(corrupt.items.value.isEmpty())
        assertTrue(corrupt.loadError.value!!.contains("библиотек", ignoreCase = true))
        assertThrows(IllegalStateException::class.java) { runBlocking { corrupt.delete("anything") } }

        val unsupportedRoot = tmp.newFolder()
        File(unsupportedRoot, "templates.json").writeText(
            """[{"id":"s","name":"x","examples":[{"library":{"schemaVersion":2,"id":"x"}}]}]""",
        )
        val unsupported = TemplateStore(unsupportedRoot, TemplateKind.LIBRARY)
        assertTrue(unsupported.items.value.isEmpty())
        assertTrue(unsupported.loadError.value!!.contains("верс", ignoreCase = true))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { unsupported.import(ByteArrayInputStream("# X\n## Задача 1\nx".toByteArray())) }
        }

        val invalidRoot = tmp.newFolder()
        File(invalidRoot, "templates.json").writeText(
            """[{"id":"s","name":"x","examples":[{"library":{"id":"not-a-uuid","version":0}}]}]""",
        )
        val invalid = TemplateStore(invalidRoot, TemplateKind.LIBRARY)
        assertTrue(invalid.items.value.isEmpty())
        assertNotNull(invalid.loadError.value)
        assertThrows(IllegalStateException::class.java) { runBlocking { invalid.delete("s") } }

        val hugeVersionRoot = tmp.newFolder()
        File(hugeVersionRoot, "templates.json").writeText(
            """[{"id":"s","name":"x","examples":[{"library":{
              "id":"00000000-0000-0000-0000-000000000001","version":2147483647}}]}]""",
        )
        val hugeVersion = TemplateStore(hugeVersionRoot, TemplateKind.LIBRARY)
        assertTrue(hugeVersion.items.value.isEmpty())
        assertNotNull(hugeVersion.loadError.value)

        val migrationRoot = tmp.newFolder()
        val live = File(migrationRoot, "templates.json")
        val legacy = """[{"id":"s","name":"x","examples":[{"condition":"old"}]}]"""
        live.writeText(legacy)
        File(migrationRoot, "templates.json.tmp").mkdir()
        val failed = TemplateStore(migrationRoot, TemplateKind.LIBRARY)
        assertTrue(failed.items.value.isEmpty())
        assertNotNull(failed.loadError.value)
        assertEquals(legacy, live.readText())
        assertThrows(IllegalStateException::class.java) { runBlocking { failed.save(null, "x", "", emptyList()) } }
        Unit
    }

    @Test
    fun exportContainsOnlyCurrentContentWithoutLibraryMetadataOrArchive() = runBlocking {
        val target = store()
        target.add()
        val section = target.items.value.single()
        val meta = section.examples.single().library!!
        target.save(section.id, section.name, section.requirements, listOf(draft(solution = "Новое",
            libraryId = meta.id, expectedVersion = meta.version)), expectedSection = section)

        val out = ByteArrayOutputStream()
        target.export(null, out, TemplateFormat.JSON)
        val text = out.toString(Charsets.UTF_8.name())
        assertFalse(text.contains("\"library\""))
        assertFalse(text.contains("previousVersions"))
        assertTrue(text.contains("Новое"))
        assertFalse(text.contains("Решение"))
    }

    @Test
    fun repeatedEquivalentLibraryImportIsUnchangedAndConflictRollsBackWholeImport() = runBlocking {
        val target = store()
        val source = """{"format":"smartcalc-templates","version":1,"templates":[
            {"id":"same","name":" Раздел ","requirements":" req ","examples":[{"condition":" A ","solution":"1",
             "solutionImages":["data:image/jpeg;base64,AQID"]}]}]}"""
        val first = target.import(ByteArrayInputStream(source.toByteArray()), "first.json")
        assertEquals(ImportResult(added = 1, updated = 0, unchanged = 0), first)
        val imported = target.items.value.single().examples.single()
        assertEquals(LibraryOrigin(LibraryOriginKind.FILE_IMPORT, "first.json"), imported.library!!.origin)
        assertEquals(LibraryVerification.UNVERIFIED, imported.library!!.verification)
        assertTrue(imported.library!!.enabled)
        val localId = imported.library!!.id
        val localPhoto = imported.solutionImages.single()

        val repeated = target.import(ByteArrayInputStream(source.toByteArray()), "second.json")
        assertEquals(ImportResult(added = 0, updated = 0, unchanged = 1), repeated)
        assertEquals(localId, target.items.value.single().examples.single().library!!.id)
        assertEquals("first.json", target.items.value.single().examples.single().library!!.origin.name)
        assertEquals(localPhoto, target.items.value.single().examples.single().solutionImages.single())
        assertEquals(1, File(localPhoto).parentFile!!.listFiles()!!.size)

        val before = target.items.value
        val conflicting = """{"format":"smartcalc-templates","version":1,"templates":[
            {"id":"new","name":"Новый","examples":[{"solutionImages":["data:image/jpeg;base64,CQk="]}]},
            {"id":"same","name":"Раздел","requirements":"req",
             "examples":[{"condition":"A","solution":"DIFFERENT","solutionImages":["data:image/jpeg;base64,CAg="]}]}]}"""
        val failure = assertThrows(TemplateImportException::class.java) {
            runBlocking { target.import(ByteArrayInputStream(conflicting.toByteArray()), "conflict.json") }
        }
        assertTrue(failure.message!!.contains("конфликт", ignoreCase = true))
        assertEquals(before, target.items.value)
        assertEquals(1, target.items.value.size)
        assertEquals(1, File(localPhoto).parentFile!!.listFiles()!!.size)
    }

    @Test
    fun equivalentDuplicateSectionIdsDeduplicateButDifferingDuplicatesRollbackBeforePhotos() = runBlocking {
        val target = store()
        val equivalentDuplicates = """{"format":"smartcalc-templates","version":1,"templates":[
          {"id":"dup","name":"D","examples":[{"condition":"A","solutionImages":["AQID"]}]},
          {"id":"dup","name":" D ","examples":[{"condition":" A ","solutionImages":["data:image/jpeg;base64,AQID"]}]}]}"""
        val first = target.import(ByteArrayInputStream(equivalentDuplicates.toByteArray()), "duplicates.json")
        assertEquals(1, first.added)
        assertEquals(1, target.items.value.size)
        val before = target.items.value
        val photoDir = File(before.single().examples.single().solutionImages.single()).parentFile!!
        val photosBefore = photoDir.listFiles()!!.map { it.name }.sorted()

        val conflict = """{"format":"smartcalc-templates","version":1,"templates":[
          {"id":"new","name":"New","examples":[{"solutionImages":["CQk="]}]},
          {"id":"dup","name":"D","examples":[{"condition":"A","solutionImages":["AQID"]}]},
          {"id":"dup","name":"D","examples":[{"condition":"DIFFERENT","solutionImages":["CAg="]}]}]}"""
        assertThrows(TemplateImportException::class.java) {
            runBlocking { target.import(ByteArrayInputStream(conflict.toByteArray()), "conflict.json") }
        }
        assertEquals(before, target.items.value)
        assertEquals(photosBefore, photoDir.listFiles()!!.map { it.name }.sorted())

        val markdownConflict = """
            # D
            <!-- id: markdown-dup -->
            ## Задача 1
            ### Условие
            A
            ---
            # D
            <!-- id: markdown-dup -->
            ## Задача 1
            ### Условие
            DIFFERENT
        """.trimIndent()
        assertThrows(TemplateImportException::class.java) {
            runBlocking { target.import(ByteArrayInputStream(markdownConflict.toByteArray()), "conflict.md") }
        }
        assertEquals(before, target.items.value)
    }

    @Test
    fun foreignMetadataPathsAndRevisionsAreNeverTrusted() = runBlocking {
        val outside = tmp.newFile("outside.jpg").apply { writeBytes(byteArrayOf(7, 7, 7)) }
        val forged = """{"format":"smartcalc-templates","version":1,"templates":[{"id":"foreign","name":"F",
          "examples":[{"condition":"A","conditionImages":["${outside.absolutePath.replace("\\", "\\\\")}"],
          "library":{"schemaVersion":999,"id":"forged","version":99,
            "origin":{"kind":"FUTURE_KIND","name":"trusted"},"verification":"SUPER_VERIFIED","enabled":false,
            "previousVersions":[{"version":98,"condition":"secret","conditionImages":["${outside.absolutePath.replace("\\", "\\\\")}"],
              "solution":"secret","solutionImages":[],"verification":"VERIFIED_BY_USER"}]}}]}]}"""

        val target = store()
        target.import(ByteArrayInputStream(forged.toByteArray()), "foreign.json")
        val example = target.items.value.single().examples.single()
        assertNotEquals("forged", example.library!!.id)
        assertEquals(1, example.library!!.version)
        assertEquals(LibraryOrigin(LibraryOriginKind.FILE_IMPORT, "foreign.json"), example.library!!.origin)
        assertEquals(LibraryVerification.UNVERIFIED, example.library!!.verification)
        assertTrue(example.library!!.enabled)
        assertTrue(example.library!!.previousVersions.isEmpty())
        assertTrue(example.conditionImages.isEmpty())
        assertArrayEquals(byteArrayOf(7, 7, 7), outside.readBytes())
    }
}
