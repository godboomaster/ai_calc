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
        val original = solution("kept", "answer").copy(imagePath = image.absolutePath)
        store.add(original)
        blockHistoryWrites(directory)

        val failure = runCatching { store.delete(original.id) }.exceptionOrNull()

        assertNotNull("Deleting should report the failed history write", failure)
        assertEquals(listOf(original), store.items.value)
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
