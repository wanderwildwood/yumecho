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
        tidyAfterAStop()
        refresh()
    }

    /**
     * What a process stopped mid-way left behind, looked at once as the app starts, when nothing
     * can still be writing: a recording cut off is given its header and kept (or let go if it
     * holds under a second); words with no recording, or half-written, are let go.
     */
    private fun tidyAfterAStop() {
        val files = dir.listFiles() ?: return
        for (f in files) {
            runCatching {
                when {
                    f.name.endsWith(".wav.part") ->
                        if (f.length() < Wav.HEADER + Wav.RATE * 2) {
                            f.delete()
                        } else {
                            java.io.RandomAccessFile(f, "rw").use { it.write(Wav.header(f.length() - Wav.HEADER)) }
                            f.renameTo(File(dir, f.name.removeSuffix(".part")))
                        }
                    f.name.endsWith(".txt.part") -> f.delete()
                    f.name.endsWith(".txt") && !File(dir, f.name.removeSuffix(".txt") + ".wav").exists() -> f.delete()
                }
            }
        }
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

    /**
     * Keeps what was heard in [dream], unless it was deleted while it was being heard. Written
     * aside and moved into place, so a stop half-way never leaves it heard as nothing.
     */
    @Synchronized
    fun heard(dream: Dream, text: String) {
        if (!audio(dream).exists()) return
        val part = File(dir, "${dream.stamp}.txt.part")
        try {
            part.writeText(text)
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        if (!part.renameTo(File(dir, "${dream.stamp}.txt"))) part.delete()
        refresh()
    }

    @Synchronized
    fun delete(dream: Dream) {
        File(dir, "${dream.stamp}.wav").delete()
        File(dir, "${dream.stamp}.txt").delete()
        refresh()
    }

    /** Writes the backup of every dream to [out]. */
    fun backup(out: java.io.OutputStream) {
        DreamBackup.write(out, all.value, { audio(it).inputStream() })
    }

    /**
     * Adds the dreams of a backup that are not here yet; see [DreamBackup]. Throws
     * [DreamBackup.BackupException] for a file that is not a good backup, having changed nothing.
     */
    fun restore(input: java.io.InputStream): DreamBackup.Added {
        val scratch = File(dir.parentFile, "dreams-restore")
        scratch.deleteRecursively()
        val staged = DreamBackup.stage(input, scratch)
        return try {
            DreamBackup.commit(staged, dir, scratch)
        } finally {
            refresh()
        }
    }

    @Synchronized
    fun refresh() {
        // A listing that failed (null) is not an empty log: keep what was known, or a transient
        // storage error would read as "every dream is gone" and delete the Nextcloud copies.
        // The same for a file that could not be read: the whole listing keeps what was known.
        val files = dir.listFiles { f -> f.name.endsWith(".wav") } ?: return
        all.value = try {
            files.mapNotNull { wav ->
                val stamp = wav.name.removeSuffix(".wav")
                val time = runCatching { LocalDateTime.parse(stamp, Dream.STAMP) }.getOrNull()
                    ?: return@mapNotNull null
                val txt = File(dir, "$stamp.txt")
                Dream(stamp, time, Wav.seconds(wav.length()), if (txt.exists()) txt.readText() else null)
            }.sortedBy { it.time }
        } catch (e: Exception) {
            return
        }
    }
}
