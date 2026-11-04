package com.kidlocaltv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class PlaybackNavigatorTest {

    private fun video(folder: String, name: String): Video {
        val id = if (folder.isEmpty()) name else "$folder/$name"
        return Video(id, folder, name, File(id))
    }

    private val library = VideoLibrary(
        listOf(
            video("Bluey", "Episode 10.mp4"),
            video("Bluey", "Episode 2.mp4"),
            video("Bluey", "Episode 1.mp4"),
            video("Trains", "Steam.mp4"),
            video("Trains", "Diesel.mp4"),
            video("", "Loose clip.mp4"),
        )
    )

    @Test
    fun episodesAreInNaturalOrder() {
        assertEquals(
            listOf("Episode 1.mp4", "Episode 2.mp4", "Episode 10.mp4"),
            library.episodesOf("Bluey").map { it.name },
        )
    }

    @Test
    fun startsInShuffleWithAVideo() {
        val nav = PlaybackNavigator(library, Random(1))
        assertNotNull(nav.start())
        assertEquals(PlaybackMode.SHUFFLE, nav.mode)
    }

    @Test
    fun shuffleUpAndDownNeverRepeatCurrentAndCoverWholeLibrary() {
        val nav = PlaybackNavigator(library, Random(42))
        var prev = nav.start()!!
        val seen = mutableSetOf(prev.id)
        repeat(60) { i ->
            val next = if (i % 2 == 0) nav.up()!! else nav.down()!!
            assertNotEquals(prev.id, next.id)
            seen += next.id
            prev = next
        }
        assertEquals(library.size, seen.size)
    }

    @Test
    fun shuffleVisitsEveryVideoBeforeRepeating() {
        val nav = PlaybackNavigator(library, Random(7))
        nav.start()
        val firstRound = (1 until library.size).map { nav.down()!!.id }
        assertEquals(firstRound.size, firstRound.toSet().size)
    }

    @Test
    fun seriesModeStepsThroughFolderAndWraps() {
        val nav = PlaybackNavigator(library, Random(3))
        nav.restore("Bluey/Episode 2.mp4", PlaybackMode.SHUFFLE)
        assertTrue(nav.enterSeriesMode())
        assertEquals(PlaybackMode.SERIES, nav.mode)
        assertEquals("Bluey/Episode 10.mp4", nav.down()!!.id)
        assertEquals("Bluey/Episode 1.mp4", nav.down()!!.id) // wraps
        assertEquals("Bluey/Episode 10.mp4", nav.up()!!.id) // wraps back
        assertEquals("Bluey/Episode 2.mp4", nav.up()!!.id)
        assertEquals(2 to 3, nav.episodeInfo())
    }

    @Test
    fun seriesModeWithSingleVideoFolderDoesNothing() {
        val nav = PlaybackNavigator(library)
        nav.restore("Loose clip.mp4", PlaybackMode.SHUFFLE)
        nav.enterSeriesMode()
        assertNull(nav.down())
        assertEquals("Loose clip.mp4", nav.current!!.id)
    }

    @Test
    fun leftReturnsToShuffle() {
        val nav = PlaybackNavigator(library)
        nav.start()
        assertFalse(nav.enterShuffleMode())
        nav.enterSeriesMode()
        assertTrue(nav.enterShuffleMode())
        assertEquals(PlaybackMode.SHUFFLE, nav.mode)
    }

    @Test
    fun endOfEpisodeInSeriesModePlaysNextEpisode() {
        val nav = PlaybackNavigator(library)
        nav.restore("Trains/Diesel.mp4", PlaybackMode.SERIES)
        assertEquals("Trains/Steam.mp4", nav.onVideoEnded()!!.id)
    }

    @Test
    fun updateLibraryDropsMissingCurrent() {
        val nav = PlaybackNavigator(library)
        nav.restore("Trains/Diesel.mp4", PlaybackMode.SERIES)
        nav.updateLibrary(VideoLibrary(listOf(video("Bluey", "Episode 1.mp4"))))
        assertNull(nav.current)
        assertEquals(PlaybackMode.SHUFFLE, nav.mode)
        assertEquals("Bluey/Episode 1.mp4", nav.down()!!.id)
    }

    @Test
    fun scanFindsVideosInFolders() {
        val root = kotlin.io.path.createTempDirectory("lib").toFile()
        try {
            File(root, "Show A").mkdirs()
            File(root, "Show A/ep1.mp4").writeText("x")
            File(root, "Show A/notes.txt").writeText("x")
            File(root, "clip.mkv").writeText("x")
            File(root, ".tmp").mkdirs()
            File(root, ".tmp/partial.mp4").writeText("x")
            val lib = VideoLibrary.scan(root)
            assertEquals(listOf("clip.mkv", "Show A/ep1.mp4"), lib.videos.map { it.id })
            assertEquals("Show A", lib.find("Show A/ep1.mp4")!!.folder)
        } finally {
            root.deleteRecursively()
        }
    }
}
