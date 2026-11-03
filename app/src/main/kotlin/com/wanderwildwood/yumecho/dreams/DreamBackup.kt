package com.wanderwildwood.yumecho.dreams

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup: every dream, its recording and its words, as one zip the person saves through the
 * system's file picker.
 *
 *     manifest.json            format, version, and each dream's name, length and words
 *     audio/20260926-031204.wav
 *
 * A dream not heard yet is in the manifest with its words null, and is heard after a restore like
 * any other. The Markdown export stays what it was, a copy for reading.
 *
 * A restore never removes or replaces anything: dreams already on the phone are left as they are,
 * and only those that are not here are added. Before any of them is, [stage] reads the whole file
 * into a scratch folder and checks every part of it; a file that fails leaves nothing behind.
 * Entry names are never used as paths: each file is named from a checked dream name.
 *
 * Not in the backup: the language dreams are heard in, the Nextcloud sign-in, the Memo switch.
 */
object DreamBackup {

    const val FORMAT = "yumecho-backup"
    const val VERSION = 1

    private const val MANIFEST = "manifest.json"
    private const val MAX_DREAMS = 50_000
    private const val MAX_AUDIO_BYTES = 400L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
    private val STAMP_SHAPE = Regex("\\d{8}-\\d{6}")

    class BackupException(val kind: Kind) : Exception(kind.name) {
        enum class Kind { NOT_A_BACKUP, NEWER, DAMAGED }
    }

    // ------------------------------------------------------------------ writing

    /** Writes the backup of [dreams] to [out]; [audio] opens a dream's recording. The stream is left open. */
    fun write(out: OutputStream, dreams: List<Dream>, audio: (Dream) -> InputStream, written: Instant = Instant.now()) {
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(manifest(dreams, written).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        for (d in dreams.sortedBy { it.time }) {
            zip.putNextEntry(ZipEntry("audio/${d.stamp}.wav"))
            audio(d).use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    internal fun manifest(dreams: List<Dream>, written: Instant): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("written", written.toString())
        root.put("dreams", JSONArray().apply {
            for (d in dreams.sortedBy { it.time }) put(JSONObject().apply {
                put("stamp", d.stamp)
                put("seconds", d.seconds)
                put("text", d.text ?: JSONObject.NULL)
            })
        })
        return root.toString(2)
    }

    // ------------------------------------------------------------------ reading

    /** A dream read out of a backup, its recording waiting in the scratch folder. */
    class Staged(val stamp: String, val text: String?, val audio: File)

    /**
     * Reads the whole backup from [input] into [scratch] (an empty folder made for this) and
     * checks it: the manifest first, then one valid recording for each dream in it, no more and
     * no fewer, and every compressed part intact. Returns what it holds, or throws a
     * [BackupException] after emptying [scratch]; the caller then has changed nothing.
     */
    fun stage(input: InputStream, scratch: File): List<Staged> {
        try {
            return stageInner(input, scratch)
        } catch (e: BackupException) {
            scratch.deleteRecursively()
            throw e
        } catch (e: Exception) {
            scratch.deleteRecursively()
            throw BackupException(BackupException.Kind.DAMAGED)
        }
    }

    private fun stageInner(input: InputStream, scratch: File): List<Staged> {
        scratch.mkdirs()
        val zip = ZipInputStream(input)
        val first = try {
            zip.nextEntry
        } catch (e: Exception) {
            null
        } ?: throw BackupException(BackupException.Kind.NOT_A_BACKUP)
        if (first.name != MANIFEST || first.isDirectory) throw BackupException(BackupException.Kind.NOT_A_BACKUP)
        val manifestBytes = readCapped(zip, MAX_MANIFEST_BYTES.toLong())
        val root = try {
            JSONObject(String(manifestBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException(BackupException.Kind.NOT_A_BACKUP)
        }
        if (root.optString("format") != FORMAT) throw BackupException(BackupException.Kind.NOT_A_BACKUP)
        val version = root.optInt("version", -1)
        if (version > VERSION) throw BackupException(BackupException.Kind.NEWER)
        if (version < 1) throw BackupException(BackupException.Kind.DAMAGED)

        val list = root.getJSONArray("dreams")
        if (list.length() > MAX_DREAMS) throw BackupException(BackupException.Kind.DAMAGED)
        val wanted = LinkedHashMap<String, String?>()
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            val stamp = o.getString("stamp")
            if (!STAMP_SHAPE.matches(stamp)) throw BackupException(BackupException.Kind.DAMAGED)
            LocalDateTime.parse(stamp, Dream.STAMP)
            val text = if (o.isNull("text")) null else o.getString("text")
            if (wanted.containsKey(stamp)) throw BackupException(BackupException.Kind.DAMAGED)
            wanted[stamp] = text
        }

        val staged = LinkedHashMap<String, Staged>()
        var total = 0L
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            val stamp = name.removePrefix("audio/").removeSuffix(".wav")
            // Only the recordings the manifest lists, each once: nothing else may be in the file.
            if (entry.isDirectory || !name.startsWith("audio/") || !name.endsWith(".wav") || stamp !in wanted || stamp in staged) {
                throw BackupException(BackupException.Kind.DAMAGED)
            }
            val file = File(scratch, "$stamp.wav")
            var size = 0L
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(buf)
                    if (n < 0) break
                    size += n
                    total += n
                    if (size > MAX_AUDIO_BYTES || total > MAX_TOTAL_BYTES) throw BackupException(BackupException.Kind.DAMAGED)
                    out.write(buf, 0, n)
                }
            }
            if (!looksLikeOurWav(file)) throw BackupException(BackupException.Kind.DAMAGED)
            staged[stamp] = Staged(stamp, wanted.getValue(stamp), file)
        }
        if (staged.size != wanted.size) throw BackupException(BackupException.Kind.DAMAGED)
        return staged.values.toList()
    }

    /** The header of the one format this app records: RIFF/WAVE, PCM, 16 kHz, mono, 16-bit, a data chunk. */
    internal fun looksLikeOurWav(file: File): Boolean {
        if (file.length() < Wav.HEADER) return false
        val head = file.inputStream().use { s -> ByteArray(Wav.HEADER).also { s.readNBytesCompat(it) } }
        val b = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun tag(at: Int) = String(head, at, 4, Charsets.US_ASCII)
        return tag(0) == "RIFF" && tag(8) == "WAVE" && tag(12) == "fmt " && b.getInt(16) == 16 &&
            b.getShort(20).toInt() == 1 && b.getShort(22).toInt() == 1 && b.getInt(24) == Wav.RATE &&
            b.getShort(34).toInt() == 16 && tag(36) == "data"
    }

    private fun InputStream.readNBytesCompat(into: ByteArray) {
        var at = 0
        while (at < into.size) {
            val n = read(into, at, into.size - at)
            if (n < 0) break
            at += n
        }
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw BackupException(BackupException.Kind.DAMAGED)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ committing

    data class Added(val added: Int, val alreadyHere: Int)

    /**
     * Moves the staged dreams that are not in [dreams] yet into it, words first and recording
     * last, so a listing never finds a recording without its words. [scratch] must be on the same
     * storage as [dreams]. Nothing already there is touched. The scratch folder is removed.
     */
    fun commit(staged: List<Staged>, dreams: File, scratch: File): Added {
        var added = 0
        var already = 0
        try {
            for (s in staged) {
                if (File(dreams, "${s.stamp}.wav").exists()) {
                    already++
                    continue
                }
                if (s.text != null) {
                    val txt = File(dreams, "${s.stamp}.txt")
                    val tmp = File(dreams, "${s.stamp}.txt.part")
                    tmp.writeText(s.text)
                    if (!tmp.renameTo(txt)) { tmp.delete(); throw java.io.IOException("txt") }
                }
                if (!s.audio.renameTo(File(dreams, "${s.stamp}.wav"))) {
                    File(dreams, "${s.stamp}.txt").delete()
                    throw java.io.IOException("wav")
                }
                added++
            }
        } finally {
            scratch.deleteRecursively()
        }
        return Added(added, already)
    }
}
