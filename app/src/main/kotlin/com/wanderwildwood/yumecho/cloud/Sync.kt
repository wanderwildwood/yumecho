package com.wanderwildwood.yumecho.cloud

import com.wanderwildwood.yumecho.dreams.Dream
import com.wanderwildwood.yumecho.dreams.NOTES_FOLDER
import com.wanderwildwood.yumecho.dreams.notePath
import com.wanderwildwood.yumecho.dreams.worthANote
import java.security.MessageDigest

/** What was sent for one dream: the file's name in the folder, and a fingerprint of its words. */
data class Sent(val name: String, val hash: String)

/** One thing to do in the dreams folder. */
sealed interface Step {
    val stamp: String
    /** Send [text] as [name]; [print] is the [wordsPrint] it is recorded under. */
    data class Put(override val stamp: String, val name: String, val text: String, val print: String) : Step
    data class Remove(override val stamp: String, val name: String) : Step
    /**
     * Nothing sent: a copy recorded under the older fingerprint, of the whole file, that is the
     * file as it would be written now, is recorded again under [print].
     */
    data class Adopt(override val stamp: String, val name: String, val print: String) : Step
}

fun fingerprint(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * A fingerprint of a dream's own words: when it was recorded and what was said, and nothing of
 * how its file is laid out. The file's heading and time line follow the phone's language and
 * 12/24-hour setting; a change to either is not a change to the dream, and must not send every
 * copy again over whatever was done to them in Nextcloud.
 */
fun wordsPrint(dream: Dream): String = fingerprint("dream-words-1\n${dream.stamp}\n${dream.text.orEmpty()}")

/**
 * Whether the copies in Nextcloud of dreams missing from the log may be removed. An empty log
 * that is answering for several copies is what a failed read of the phone's storage looks like,
 * and acting on it would delete every copy: so nothing is removed then. One dream deleted by the
 * reader still removes its own copy, including the last one.
 */
fun removalsAreSafe(dreams: List<Dream>, sent: Map<String, Sent>): Boolean = removalsAreSafe(dreams, sent.size)

/** The same rule for anything else that follows the log, such as the notes in Memo. */
fun removalsAreSafe(dreams: List<Dream>, copies: Int): Boolean = dreams.isNotEmpty() || copies <= 1

/**
 * What it takes to make the folder match the log: a copy of every dream that has words and has
 * not been sent, or whose words have changed since (by [wordsPrint], so a new language or clock
 * setting sends nothing); and the removal of the copy of every dream
 * that is gone. A dream keeps the name it was first sent under, so nothing is renamed. Names
 * are the time the recording began ("2026-10-05 0712.md"), as in Notes.
 *
 * [render] is a dream as the text of its file. Worked out without a phone or a network.
 */
fun plan(dreams: List<Dream>, sent: Map<String, Sent>, render: (Dream) -> String): List<Step> {
    val steps = mutableListOf<Step>()
    val here = dreams.mapTo(HashSet()) { it.stamp }
    if (removalsAreSafe(dreams, sent)) for ((stamp, was) in sent) if (stamp !in here) steps += Step.Remove(stamp, was.name)
    val taken = sent.values.mapTo(HashSet()) { it.name }
    for (dream in dreams) {
        if (!worthANote(dream)) continue
        val was = sent[dream.stamp]
        val print = wordsPrint(dream)
        if (was != null) {
            if (was.hash == print) continue
            val text = render(dream)
            steps += if (was.hash == fingerprint(text)) Step.Adopt(dream.stamp, was.name, print)
            else Step.Put(dream.stamp, was.name, text, print)
            continue
        }
        val name = notePath(dream.time, taken.map { "$NOTES_FOLDER/$it" }.toSet()).removePrefix("$NOTES_FOLDER/")
        taken += name
        steps += Step.Put(dream.stamp, name, render(dream), print)
    }
    return steps
}

/**
 * Carries out [plan] against the folder. [record] hears about each step as it succeeds (a
 * fingerprint for a copy sent, null for one removed), so that a failure partway keeps what was
 * done. The first failure ends the run and is thrown: the next change to the log, or the next
 * time the app opens, tries again.
 */
fun sync(
    folder: DreamFolder,
    dreams: List<Dream>,
    sent: Map<String, Sent>,
    render: (Dream) -> String,
    record: (stamp: String, Sent?) -> Unit,
) {
    val steps = plan(dreams, sent, render)
    if (steps.isEmpty()) return
    if (steps.any { it is Step.Put }) folder.makeFolders()
    for (step in steps) {
        when (step) {
            is Step.Put -> {
                folder.put(step.name, step.text)
                record(step.stamp, Sent(step.name, step.print))
            }
            is Step.Adopt -> record(step.stamp, Sent(step.name, step.print))
            is Step.Remove -> {
                folder.delete(step.name)
                record(step.stamp, null)
            }
        }
    }
}
