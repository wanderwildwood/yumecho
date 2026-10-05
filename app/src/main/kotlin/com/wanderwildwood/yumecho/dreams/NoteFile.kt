package com.wanderwildwood.yumecho.dreams

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Where dreams go in Notes, relative to wherever Notes keeps its notes. */
const val NOTES_FOLDER = "Dreams"

private val NOTE_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")

/**
 * The note a dream is kept in: "Dreams/2026-10-05 0712.md", for when the recording began.
 *
 * Named for the time rather than the words, so the name does not change under the reader in
 * Notes, and so one sorts beside the next in the folder. Two recordings can begin in the same
 * minute; the second is "0712 (2)". [taken] is every path already given to another dream, and
 * a path once given is kept for that dream, so this is asked only once per dream.
 */
fun notePath(time: LocalDateTime, taken: Set<String>): String {
    val name = time.format(NOTE_NAME)
    var path = "$NOTES_FOLDER/$name.md"
    var n = 2
    while (path in taken) path = "$NOTES_FOLDER/$name (${n++}).md"
    return path
}

/**
 * A dream as a note: the night as its title, the time and length under it, then the words,
 * three sentences to a paragraph as in the log. The recording is not in it.
 *
 * The wording comes in from outside, as for [exportText], so the headings are in the phone's
 * language and this can be tested without a phone.
 */
fun noteText(dream: Dream, title: String, timeAndLength: String): String = buildString {
    append("# ").append(title).append("\n\n")
    append(timeAndLength).append("\n\n")
    append(paragraphs(dream.text.orEmpty()).joinToString("\n\n")).append("\n")
}

/** Only a dream with words in it is worth a note; one not heard yet waits until it has been. */
fun worthANote(dream: Dream): Boolean = !dream.text.isNullOrBlank()
