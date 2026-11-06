package com.yangfei.xingmu.data.model

import org.junit.Assert.*
import org.junit.Test

class ResumeTargetTest {
    private val sources = listOf(
        PlaySource("one", listOf(Episode("第1集", "a"))),
        PlaySource("two", listOf(Episode("第2集", "b"), Episode("第3集", "c"))),
    )
    private fun entry(episode: String, progress: Float) = LibraryEntry("site", "id", "name", episodeName = episode, progress = progress)

    @Test fun matchesRecordedEpisodeAcrossSources() {
        assertEquals(ResumeTarget(1, 1, 0.45f), findResumeTarget(sources, entry("第3集", 0.45f)))
    }

    @Test fun doesNotResumeMissingOrCompletedEpisode() {
        assertNull(findResumeTarget(sources, entry("不存在", 0.5f)))
        assertNull(findResumeTarget(sources, entry("第1集", 0.99f)))
        assertNull(findResumeTarget(sources, entry("第1集", 0f)))
        assertNull(findResumeTarget(sources, null))
    }

    @Test fun prefersSavedSourceWhenEpisodeNamesOverlap() {
        val lines = listOf(
            PlaySource("line A", listOf(Episode("第1集", "a"))),
            PlaySource("line B", listOf(Episode("第1集", "b"))),
        )
        val saved = entry("第1集", 0.4f).copy(sourceName = "line B")
        assertEquals(ResumeTarget(1, 0, 0.4f), findResumeTarget(lines, saved))
        assertEquals(ResumeTarget(0, 0, 0.4f), findResumeTarget(lines, saved.copy(sourceName = "old line")))
    }
}
