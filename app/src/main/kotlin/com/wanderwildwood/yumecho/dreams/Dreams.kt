package com.wanderwildwood.yumecho.dreams

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDateTime

/**
 * Every recording, kept as plain files in the app's own storage:
 *
 *     dreams/20260926-031204.wav    the recording, which is the real thing
 *     dreams/20260926-031204.txt    what Whisper heard in it, once it has listened
 *
 * No database: a night's worth is a handful of files, and a file that is there is a
 * recording that is there. A recording still being made is `.wav.part` until it stops, so a
 * half-written one is never listed or transcribed.
 */
object Dreams {
    private lateinit var dir: File
    private val all = MutableStateFlow<List<Dream>>(emptyList())
    val list: StateFlow<List<Dream>> = all

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "dreams").apply { mkdirs() }
        refresh()
    }

    /** Where the next recording goes while it is being made. */
    fun newRecording(time: LocalDateTime): File = File(dir, "${time.format(Dream.STAMP)}.wav.part")

    /** A finished recording, named so that it is listed and transcribed. */
    fun finish(part: File): File {
        val done = File(dir, part.name.removeSuffix(".part"))
        part.renameTo(done)
        refresh()
        return done
    }

    fun audio(dream: Dream): File = File(dir, "${dream.stamp}.wav")

    fun waiting(): List<Dream> = all.value.filter { it.text == null }

    fun heard(dream: Dream, text: String) {
        File(dir, "${dream.stamp}.txt").writeText(text)
        refresh()
    }

    fun delete(dream: Dream) {
        File(dir, "${dream.stamp}.wav").delete()
        File(dir, "${dream.stamp}.txt").delete()
        refresh()
    }

    @Synchronized
    fun refresh() {
        all.value = dir.listFiles { f -> f.name.endsWith(".wav") }.orEmpty().mapNotNull { wav ->
            val stamp = wav.name.removeSuffix(".wav")
            val time = runCatching { LocalDateTime.parse(stamp, Dream.STAMP) }.getOrNull()
                ?: return@mapNotNull null
            val txt = File(dir, "$stamp.txt")
            Dream(stamp, time, Wav.seconds(wav.length()), if (txt.exists()) txt.readText() else null)
        }.sortedBy { it.time }
    }
}
