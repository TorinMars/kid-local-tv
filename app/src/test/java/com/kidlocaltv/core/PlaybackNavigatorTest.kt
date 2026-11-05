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
        return Video(id, folder, name, VideoLocation.LocalFile(File(id)))
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
    fun leftAndRightStepThroughFolderInShuffleModeAndKeepTheMode() {
        val nav = PlaybackNavigator(library, Random(3))
        nav.restore("Bluey/Episode 2.mp4", PlaybackMode.SHUFFLE)
        assertEquals("Bluey/Episode 10.mp4", nav.nextEpisode()!!.id)
        assertEquals("Bluey/Episode 1.mp4", nav.nextEpisode()!!.id) // wraps
        assertEquals("Bluey/Episode 10.mp4", nav.previousEpisode()!!.id) // wraps back
        assertEquals(PlaybackMode.SHUFFLE, nav.mode)
        // Up/Down still pick random videos.
        assertNotEquals("Bluey/Episode 10.mp4", nav.down()!!.id)
    }

    @Test
    fun leftAndRightInSingleVideoFolderDoNothing() {
        val nav = PlaybackNavigator(library)
        nav.restore("Loose clip.mp4", PlaybackMode.SHUFFLE)
        assertNull(nav.nextEpisode())
        assertNull(nav.previousEpisode())
        assertEquals("Loose clip.mp4", nav.current!!.id)
        assertTrue(nav.episodeNeighbours().isEmpty())
    }

    @Test
    fun endOfVideoInShuffleModePlaysNextEpisode() {
        val nav = PlaybackNavigator(library, Random(5))
        nav.restore("Bluey/Episode 1.mp4", PlaybackMode.SHUFFLE)
        assertEquals("Bluey/Episode 2.mp4", nav.onVideoEnded()!!.id)
        assertEquals("Bluey/Episode 10.mp4", nav.onVideoEnded()!!.id)
        assertEquals("Bluey/Episode 1.mp4", nav.onVideoEnded()!!.id) // wraps to the first
        assertEquals(PlaybackMode.SHUFFLE, nav.mode)
    }

    @Test
    fun endOfLoneVideoInShuffleModePlaysAnotherVideo() {
        val nav = PlaybackNavigator(library, Random(5))
        nav.restore("Loose clip.mp4", PlaybackMode.SHUFFLE)
        assertNotEquals("Loose clip.mp4", nav.onVideoEnded()!!.id)
    }

    @Test
    fun episodeNeighboursAreNextThenPrevious() {
        val nav = PlaybackNavigator(library)
        nav.restore("Bluey/Episode 2.mp4", PlaybackMode.SHUFFLE)
        assertEquals(listOf("Episode 10.mp4", "Episode 1.mp4"), nav.episodeNeighbours().map { it.name })
        nav.restore("Trains/Diesel.mp4", PlaybackMode.SHUFFLE)
        assertEquals(listOf("Steam.mp4"), nav.episodeNeighbours().map { it.name }) // two episodes: listed once
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

    // ---- preview (used to preload what Up/Down will play) ----

    @Test
    fun shufflePreviewIsExactlyWhatUpAndDownPlayNext() {
        val nav = PlaybackNavigator(library, Random(7))
        nav.start()
        repeat(20) { round ->
            val preview = nav.preview(ahead = 2, behind = 1)
            assertEquals(3, preview.size)
            // Up and Down both take the next random pick in shuffle mode.
            val next = if (round % 2 == 0) nav.down()!! else nav.up()!!
            assertEquals(preview.first().id, next.id)
        }
    }

    @Test
    fun shufflePreviewDoesNotMoveAndIsStable() {
        val nav = PlaybackNavigator(library, Random(3))
        val first = nav.start()!!
        val a = nav.preview(2, 1).map { it.id }
        val b = nav.preview(2, 1).map { it.id }
        assertEquals(a, b)
        assertEquals(first.id, nav.current!!.id)
        assertFalse(a.contains(first.id))
        assertEquals(a.size, a.toSet().size)
    }

    @Test
    fun shufflePreviewNeverPlaysTheSameVideoTwiceInARow() {
        val nav = PlaybackNavigator(library, Random(11))
        var prev = nav.start()!!
        repeat(80) {
            nav.preview(2, 1)
            val next = nav.down()!!
            assertNotEquals(prev.id, next.id)
            prev = next
        }
    }

    @Test
    fun seriesPreviewIsNextThenPreviousThenFurther() {
        val nav = PlaybackNavigator(library, Random(1))
        nav.restore("Bluey/Episode 2.mp4", PlaybackMode.SHUFFLE)
        nav.enterSeriesMode()
        val preview = nav.preview(ahead = 2, behind = 1).map { it.name }
        // Episode 2: next is 10, previous is 1; two ahead wraps to 1, which is already listed.
        assertEquals(listOf("Episode 10.mp4", "Episode 1.mp4"), preview)
        assertEquals("Episode 10.mp4", nav.down()!!.name)
    }

    @Test
    fun previewOfATinyLibraryIsEmptyOrShort() {
        val one = VideoLibrary(listOf(video("", "only.mp4")))
        val nav = PlaybackNavigator(one, Random(1))
        nav.start()
        assertTrue(nav.preview(2, 1).isEmpty())

        val two = VideoLibrary(listOf(video("", "a.mp4"), video("", "b.mp4")))
        val nav2 = PlaybackNavigator(two, Random(1))
        val start = nav2.start()!!
        val preview = nav2.preview(2, 1)
        assertEquals(1, preview.size)
        assertNotEquals(start.id, preview.first().id)
        assertEquals(preview.first().id, nav2.down()!!.id)
    }

    @Test
    fun newLibraryDropsStalePreview() {
        val nav = PlaybackNavigator(library, Random(5))
        nav.start()
        nav.preview(2, 1)
        val smaller = VideoLibrary(listOf(video("", "x.mp4"), video("", "y.mp4"), video("", "z.mp4")))
        nav.updateLibrary(smaller)
        val ids = smaller.videos.map { it.id }.toSet()
        assertTrue(nav.preview(2, 1).all { it.id in ids })
        assertTrue(nav.down()!!.id in ids)
    }

    @Test
    fun fiveVideoShufflePreviewHasNoRepeatsAndMatchesPlayback() {
        val nav = PlaybackNavigator(library, Random(19))
        nav.start()
        repeat(40) {
            val preview = nav.preview(ahead = 4, behind = 1)
            // Six videos, one playing: the five others, each once.
            assertEquals(5, preview.size)
            assertEquals(5, preview.map { it.id }.toSet().size)
            assertFalse(preview.any { it.id == nav.current!!.id })
            assertEquals(preview.first().id, nav.down()!!.id)
        }
    }
}
