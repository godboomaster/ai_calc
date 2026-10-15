package com.smartcalc.app.data.history

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun addUpsertsSameIdOnceAndMovesReplacementToFront() = runBlocking {
        val store = HistoryStore(temporaryFolder.newFolder("upsert"))
        store.add(solution("same", "old answer"))
        store.add(solution("other", "other answer"))

        store.add(solution("same", "new answer"))

        assertEquals(listOf("same", "other"), store.items.value.map { it.id })
        assertEquals("new answer", store.items.value.first().answer)
    }

    @Test
    fun addLocalizesEveryImageAndDeduplicatesRepeatedReferences() = runBlocking {
        val directory = temporaryFolder.newFolder("localize-all")
        val sources = temporaryFolder.newFolder("library-images")
        val condition = File(sources, "condition.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val step = File(sources, "step.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val followUp = File(sources, "follow-up.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val store = HistoryStore(directory)
        val original = solution("found", "answer").copy(
            imagePath = condition.absolutePath,
            source = "База · Геометрия",
            steps = listOf(SolutionStep("step", "details", images = listOf(condition.absolutePath, step.absolutePath))),
            followUps = listOf(
                FollowUp(
                    "why?",
                    "because",
                    steps = listOf(SolutionStep("follow-up", "details", images = listOf(step.absolutePath, followUp.absolutePath))),
                    timestamp = 20L,
                ),
            ),
        )

        store.add(original)

        val saved = store.items.value.single()
        val localized = listOfNotNull(saved.imagePath) + saved.steps.flatMap { it.images } +
            saved.followUps.flatMap { item -> item.steps.flatMap { it.images } }
        val ownedDirectory = File(directory, "images").canonicalFile
        assertTrue(localized.all { File(it).canonicalFile.parentFile == ownedDirectory })
        assertEquals("Repeated source paths must reuse one history-owned copy", 3, localized.distinct().size)
        assertArrayEquals(condition.readBytes(), File(saved.imagePath!!).readBytes())
        assertArrayEquals(step.readBytes(), File(saved.steps.single().images.last()).readBytes())
        assertArrayEquals(followUp.readBytes(), File(saved.followUps.single().steps.single().images.last()).readBytes())
        assertTrue("Opening a found solution must not consume the library image", condition.exists())
        assertTrue(step.exists())
        assertTrue(followUp.exists())
    }

    @Test
    fun addWithMissingImageFailsWithoutPublishingOrLeavingCopies() = runBlocking {
        val directory = temporaryFolder.newFolder("missing-source")
        val sources = temporaryFolder.newFolder("missing-source-images")
        val present = File(sources, "present.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val missing = File(sources, "missing.jpg")
        val store = HistoryStore(directory)
        val candidate = solution("missing", "answer").copy(
            imagePath = present.absolutePath,
            source = "База",
            steps = listOf(SolutionStep("step", "details", images = listOf(missing.absolutePath))),
        )

        val failure = runCatching { store.add(candidate) }.exceptionOrNull()

        assertNotNull("A missing referenced image must fail the save", failure)
        assertTrue(store.items.value.isEmpty())
        assertTrue(File(directory, "images").listFiles().isNullOrEmpty())
        assertTrue(present.exists())
    }

    @Test
    fun reopeningFoundSolutionPreservesExistingFollowUps() = runBlocking {
        val directory = temporaryFolder.newFolder("reopen-found")
        val store = HistoryStore(directory)
        val opened = solution("found", "answer").copy(source = "База")
        val followedUp = opened.copy(
            followUps = listOf(FollowUp("why?", "because", timestamp = 20L)),
        )
        store.add(followedUp)

        store.add(opened)

        assertEquals(followedUp, store.items.value.single())
    }

    @Test
    fun replayingFollowUpUpdateDoesNotDuplicateSolutionOrFollowUp() = runBlocking {
        val store = HistoryStore(temporaryFolder.newFolder("follow-up"))
        val base = solution("stable", "base answer")
        val updated = base.copy(
            followUps = listOf(FollowUp("why?", "because", timestamp = 20L)),
        )
        store.add(base)

        store.update(updated)
        store.update(updated)

        assertEquals(1, store.items.value.size)
        assertEquals(listOf(FollowUp("why?", "because", timestamp = 20L)), store.items.value.single().followUps)
    }

    @Test
    fun conditionalUpdateDoesNotResurrectDeletedBase() = runBlocking {
        val directory = temporaryFolder.newFolder("deleted-base")
        val store = HistoryStore(directory)
        val base = solution("deleted", "base answer")
        store.add(base)
        store.delete(base.id)

        val applied = store.update(
            base.copy(followUps = listOf(FollowUp("late question", "late answer", timestamp = 20L))),
            requireExisting = true,
        )

        assertFalse(applied)
        assertTrue(store.items.value.isEmpty())
        assertTrue(HistoryStore(directory).items.value.isEmpty())
    }

    @Test
    fun failedDeleteKeepsPublishedStateAndImage() = runBlocking {
        val directory = temporaryFolder.newFolder("failed-delete")
        val store = HistoryStore(directory)
        val image = File(store.saveImage(byteArrayOf(1, 2, 3)))
        // add() хранит канонический путь: на macOS /var/… временной папки становится /private/var/….
        val published = store.add(solution("kept", "answer").copy(imagePath = image.absolutePath))
        blockHistoryWrites(directory)

        val failure = runCatching { store.delete(published.id) }.exceptionOrNull()

        assertNotNull("Deleting should report the failed history write", failure)
        assertEquals(listOf(published), store.items.value)
        assertTrue("Image must remain when history write fails", image.exists())
    }

    @Test
    fun deleteDoesNotRemoveImageOutsideOwnedDirectory() = runBlocking {
        val directory = temporaryFolder.newFolder("external-image")
        val externalImage = temporaryFolder.newFile("external.jpg").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val store = HistoryStore(directory)
        val original = solution("external", "answer").copy(imagePath = externalImage.absolutePath)
        store.add(original)

        store.delete(original.id)

        assertTrue("History must not delete files outside its images directory", externalImage.exists())
        assertTrue(store.items.value.isEmpty())
    }

    @Test
    fun deleteCleansAllUnreferencedNestedImagesButPreservesSharedImages() = runBlocking {
        val directory = temporaryFolder.newFolder("delete-nested")
        val store = HistoryStore(directory)
        val shared = store.saveImage(byteArrayOf(1), "123e4567-e89b-12d3-a456-426614174010")
        val privateStep = store.saveImage(byteArrayOf(2), "123e4567-e89b-12d3-a456-426614174011")
        val privateFollowUp = store.saveImage(byteArrayOf(3), "123e4567-e89b-12d3-a456-426614174012")
        store.add(
            solution("removed", "answer").copy(
                imagePath = shared,
                steps = listOf(SolutionStep("step", "details", images = listOf(privateStep))),
                followUps = listOf(
                    FollowUp(
                        "next",
                        "result",
                        steps = listOf(SolutionStep("follow", "details", images = listOf(privateFollowUp))),
                        timestamp = 20L,
                    ),
                ),
            ),
        )
        store.add(
            solution("survivor", "answer").copy(
                followUps = listOf(
                    FollowUp(
                        "shared",
                        "result",
                        steps = listOf(SolutionStep("follow", "details", images = listOf(shared))),
                        timestamp = 30L,
                    ),
                ),
            ),
        )

        store.delete("removed")

        assertTrue("A surviving nested reference owns the shared image", File(shared).exists())
        assertFalse("Deleted solution step images must be cleaned", File(privateStep).exists())
        assertFalse("Deleted solution follow-up images must be cleaned", File(privateFollowUp).exists())
    }

    @Test
    fun updateCleansRemovedNestedImageAfterPublishingMetadata() = runBlocking {
        val directory = temporaryFolder.newFolder("update-cleanup")
        val store = HistoryStore(directory)
        val removed = store.saveImage(byteArrayOf(1), "123e4567-e89b-12d3-a456-426614174013")
        val kept = store.saveImage(byteArrayOf(2), "123e4567-e89b-12d3-a456-426614174014")
        val original = solution("updated", "answer").copy(
            steps = listOf(SolutionStep("step", "details", images = listOf(removed, kept))),
        )
        store.add(original)

        store.update(original.copy(steps = listOf(SolutionStep("step", "details", images = listOf(kept)))))

        assertFalse(File(removed).exists())
        assertTrue(File(kept).exists())
        assertEquals(listOf(kept), store.items.value.single().steps.single().images)
        assertEquals(store.items.value, HistoryStore(directory).items.value)
    }

    @Test
    fun failedLocalizedAddLeavesNoCopiesOrPublishedState() = runBlocking {
        val directory = temporaryFolder.newFolder("failed-localized-add")
        val source = temporaryFolder.newFile("localized-source.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val store = HistoryStore(directory)
        File(directory, "history.json.tmp").mkdir()

        val failure = runCatching {
            store.add(solution("failed", "answer").copy(imagePath = source.absolutePath, source = "База"))
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(store.items.value.isEmpty())
        assertTrue(File(directory, "images").listFiles().isNullOrEmpty())
        assertTrue(source.exists())
    }

    @Test
    fun reloadPreservesSavedHistory() = runBlocking {
        val directory = temporaryFolder.newFolder("reload")
        val expected = solution("saved", "answer").copy(
            steps = listOf(SolutionStep("step", "details")),
            followUps = listOf(FollowUp("next", "result", timestamp = 30L)),
        )
        HistoryStore(directory).add(expected)

        val reloaded = HistoryStore(directory)

        assertEquals(listOf(expected), reloaded.items.value)
        assertEquals(listOf("history.json"), directory.list()?.sorted())
    }

    @Test
    fun stableImageIdReusesPathAndPreservesBytes() = runBlocking {
        val directory = temporaryFolder.newFolder("stable-image")
        val store = HistoryStore(directory)
        val stableId = "123e4567-e89b-12d3-a456-426614174000"
        val jpeg = byteArrayOf(9, 8, 7, 6)

        val firstPath = store.saveImage(jpeg, stableId)
        val secondPath = store.saveImage(jpeg, stableId)

        assertEquals(firstPath, secondPath)
        assertEquals("$stableId.jpg", File(firstPath).name)
        assertArrayEquals(jpeg, File(secondPath).readBytes())
        assertEquals(listOf("$stableId.jpg"), File(directory, "images").list()?.sorted())
    }

    @Test
    fun stableImageIdRejectsHostileAndInvalidValues() = runBlocking {
        val directory = temporaryFolder.newFolder("invalid-image-id")
        val store = HistoryStore(directory)

        listOf("../outside", "not-a-uuid", "123e4567-e89b-12d3-a456-426614174000/../escape").forEach { id ->
            val failure = runCatching { store.saveImage(byteArrayOf(1), id) }.exceptionOrNull()
            assertTrue("Expected invalid stable ID to fail: $id", failure is IllegalArgumentException)
        }

        assertTrue(File(directory, "images").listFiles().isNullOrEmpty())
    }

    @Test
    fun removesUnreferencedOwnedImage() = runBlocking {
        val directory = temporaryFolder.newFolder("remove-unreferenced")
        val store = HistoryStore(directory)
        val path = store.saveImage(
            byteArrayOf(1, 2, 3),
            "123e4567-e89b-12d3-a456-426614174001",
        )

        store.removeImageIfUnreferenced(path)

        assertFalse(File(path).exists())
    }

    @Test
    fun retainsReferencedOwnedImage() = runBlocking {
        val directory = temporaryFolder.newFolder("retain-referenced")
        val store = HistoryStore(directory)
        val path = store.saveImage(
            byteArrayOf(1, 2, 3),
            "123e4567-e89b-12d3-a456-426614174002",
        )
        store.add(solution("with-image", "answer").copy(imagePath = path))

        store.removeImageIfUnreferenced(path)

        assertTrue(File(path).exists())
    }

    @Test
    fun retainsImageReferencedByStepOrFollowUp() = runBlocking {
        val directory = temporaryFolder.newFolder("retain-nested-references")
        val store = HistoryStore(directory)
        val stepPath = store.saveImage(byteArrayOf(1), "123e4567-e89b-12d3-a456-426614174015")
        val followUpPath = store.saveImage(byteArrayOf(2), "123e4567-e89b-12d3-a456-426614174016")
        store.add(
            solution("nested-images", "answer").copy(
                steps = listOf(SolutionStep("step", "details", images = listOf(stepPath))),
                followUps = listOf(
                    FollowUp(
                        "next",
                        "result",
                        steps = listOf(SolutionStep("follow", "details", images = listOf(followUpPath))),
                        timestamp = 20L,
                    ),
                ),
            ),
        )

        store.removeImageIfUnreferenced(stepPath)
        store.removeImageIfUnreferenced(followUpPath)

        assertTrue(File(stepPath).exists())
        assertTrue(File(followUpPath).exists())
    }

    @Test
    fun refusesToRemoveExternalUnreferencedImage() = runBlocking {
        val directory = temporaryFolder.newFolder("refuse-external")
        val store = HistoryStore(directory)
        val external = temporaryFolder.newFile("unreferenced-external.jpg")

        store.removeImageIfUnreferenced(external.absolutePath)

        assertTrue(external.exists())
    }

    private fun solution(id: String, answer: String) = Solution(
        id = id,
        problem = "problem-$id",
        answer = answer,
        timestamp = 10L,
    )

    private fun blockHistoryWrites(directory: File) {
        val historyFile = File(directory, "history.json")
        assertTrue(historyFile.renameTo(File(directory, "history.previous")))
        assertTrue(historyFile.mkdir())
    }
}
