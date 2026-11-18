package com.wanderwildwood.yumecho.night

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.wanderwildwood.yumecho.dreams.Dreams
import com.wanderwildwood.yumecho.dreams.Wav
import com.wanderwildwood.yumecho.hearing.Listening
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import kotlin.math.sqrt

/**
 * The microphone, a second at a time, into a file.
 *
 * It stops itself after [QUIET_STOP] seconds of quiet, because someone who has just said their
 * dream and gone back to sleep will not press the key again, and at [LONGEST] seconds, because
 * a recording left running all night is not a dream.
 */
class Recorder(private val onFinished: (File?, Stop) -> Unit) {
    enum class Stop { PRESSED, QUIET, LONGEST, FAILED }

    companion object {
        const val QUIET_STOP = 20
        const val LONGEST = 5 * 60
    }

    /** One recording's own stop word, so a recording finishing can never stop the next one. */
    private class Capture { @Volatile var running = true }

    /** The recording under way, until its file is finished; null when there is none. */
    @Volatile private var capture: Capture? = null
    private var thread: Thread? = null

    val isRecording get() = capture != null

    /** False if the microphone could not be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (capture != null) return false
        val rate = Wav.RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val part = Dreams.newRecording(LocalDateTime.now())
        val mine = Capture()
        capture = mine
        thread = Thread({ capture(mine, record, part) }, "recorder").apply { start() }
        return true
    }

    fun stop() {
        capture?.running = false
        thread?.join(3000)
        if (thread?.isAlive != true) thread = null
    }

    private fun capture(c: Capture, record: AudioRecord, part: File) {
        val rate = Wav.RATE
        val second = ShortArray(rate)
        val bytes = ByteArray(rate * 2)
        var quiet = 0
        var seconds = 0
        var why = Stop.PRESSED
        var out: RandomAccessFile? = null
        try {
            val file = RandomAccessFile(part, "rw").also { out = it }
            file.setLength(0)
            file.write(ByteArray(Wav.HEADER))
            record.startRecording()
            while (c.running) {
                var filled = 0
                while (filled < rate && c.running) {
                    val n = record.read(second, filled, rate - filled)
                    if (n < 0) { why = Stop.FAILED; c.running = false; break }
                    filled += n
                }
                var sum = 0.0
                for (i in 0 until filled) {
                    val s = second[i].toInt()
                    sum += (s * s).toDouble()
                    bytes[i * 2] = (s and 0xff).toByte()
                    bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                }
                file.write(bytes, 0, filled * 2)
                seconds++
                val rms = if (filled > 0) sqrt(sum / filled) else 0.0
                quiet = if (rms < Listening.QUIET_RMS) quiet + 1 else 0
                if (quiet >= QUIET_STOP) { why = Stop.QUIET; c.running = false }
                if (seconds >= LONGEST) { why = Stop.LONGEST; c.running = false }
            }
        } catch (e: Exception) {
            // Storage full or failing, or the microphone refusing to start: this recording ends
            // (what was written is kept), and the night stays armed for the next press.
            why = Stop.FAILED
        } finally {
            // Released in a finally, or a failed read leaves the microphone held and the
            // next press finds it busy.
            runCatching { record.stop() }
            record.release()
            runCatching {
                out?.let {
                    it.seek(0)
                    it.write(Wav.header(it.length() - Wav.HEADER))
                }
            }
            runCatching { out?.close() }
            c.running = false
            // Never thrown out of here: this thread ending in an error would end the app, and
            // the night armed with it.
            val done = runCatching { Dreams.finish(part) }.getOrNull()
            capture = null
            onFinished(done, why)
        }
    }
}
