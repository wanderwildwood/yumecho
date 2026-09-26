package com.wanderwildwood.yumecho.dreams

import java.time.LocalDate

/**
 * Every dream's words as one Markdown file, to be taken off the phone and written up.
 *
 * It reads forward like a journal, oldest night first, rather than newest first as the log
 * does: the log is for this morning, and the file is for sitting down with later. A recording
 * in which nothing was said is left out, since there is nothing in it to write up. One not
 * heard yet is kept, under [notHeard], so the file does not quietly hold fewer dreams than
 * the phone does. The recordings themselves are not in it.
 *
 * The wording comes in from outside so that the headings are in the phone's language, and so
 * that this can be tested without a phone.
 */
fun exportText(
    dreams: List<Dream>,
    title: String,
    nightHeading: (LocalDate) -> String,
    dreamHeading: (Dream) -> String,
    notHeard: String,
): String = buildString {
    append("# ").append(title).append("\n")
    for ((night, list) in byNight(dreams).reversed()) {
        val kept = list.filter { it.text == null || it.text.isNotBlank() }
        if (kept.isEmpty()) continue
        append("\n## ").append(nightHeading(night)).append("\n")
        for (dream in kept) {
            append("\n### ").append(dreamHeading(dream)).append("\n\n")
            val text = dream.text
            if (text == null) {
                append("_").append(notHeard).append("_\n")
            } else {
                append(paragraphs(text).joinToString("\n\n")).append("\n")
            }
        }
    }
}

/** Whisper's text, three sentences to a paragraph. It has no paragraphs of its own. */
fun paragraphs(text: String): List<String> =
    text.trim().split(Regex("(?<=[.!?])\\s+"))
        .filter { it.isNotBlank() }
        .chunked(3) { it.joinToString(" ") }
