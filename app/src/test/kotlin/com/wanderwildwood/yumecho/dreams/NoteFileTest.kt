package com.wanderwildwood.yumecho.dreams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NoteFileTest {
    private fun dream(stamp: String, text: String?) =
        Dream(stamp, LocalDateTime.parse(stamp, Dream.STAMP), 75, text)

    @Test
    fun pathIsTheMinuteTheRecordingBegan() {
        assertEquals("Dreams/2026-10-05 0712.md", notePath(LocalDateTime.of(2026, 10, 5, 7, 12, 41), emptySet()))
        assertEquals("Dreams/2026-01-02 0003.md", notePath(LocalDateTime.of(2026, 1, 2, 0, 3, 0), emptySet()))
    }

    @Test
    fun aSecondRecordingInTheSameMinuteIsNumbered() {
        val time = LocalDateTime.of(2026, 10, 5, 3, 40, 2)
        val first = notePath(time, emptySet())
        val second = notePath(time.plusSeconds(30), setOf(first))
        val third = notePath(time.plusSeconds(50), setOf(first, second))
        assertEquals("Dreams/2026-10-05 0340.md", first)
        assertEquals("Dreams/2026-10-05 0340 (2).md", second)
        assertEquals("Dreams/2026-10-05 0340 (3).md", third)
    }

    @Test
    fun pathDoesNotDependOnTheWords() {
        val a = dream("20261005-034002", "A lighthouse made of bread.")
        val b = a.copy(text = "Something else entirely.")
        assertEquals(notePath(a.time, emptySet()), notePath(b.time, emptySet()))
    }

    @Test
    fun noteHasTitleTimeAndParagraphs() {
        val text = noteText(
            dream("20261005-034002", " The stairs went down. A fox. It sang. Then the tide came in."),
            title = "Night of Sunday 4 October 2026",
            timeAndLength = "03:40 · 1 minute",
        )
        assertEquals(
            """
            # Night of Sunday 4 October 2026

            03:40 · 1 minute

            The stairs went down. A fox. It sang.

            Then the tide came in.

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun onlyDreamsWithWordsAreSent() {
        assertTrue(worthANote(dream("20261005-034002", "A moth.")))
        assertFalse(worthANote(dream("20261005-034002", null)))
        assertFalse(worthANote(dream("20261005-034002", "")))
        assertFalse(worthANote(dream("20261005-034002", "  ")))
    }
}
