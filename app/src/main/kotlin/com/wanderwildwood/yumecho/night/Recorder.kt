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
class Recorder(private val onFinished: (File, Stop) -> Unit) {
    enum class Stop { PRESSED, QUIET, LONGEST, FAILED }

    companion object {
        const val QUIET_STOP = 20
        const val LONGEST = 5 * 60
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRecording get() = running

    /** False if the microphone could not be opened. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
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
        running = true
        thread = Thread({ capture(record, part) }, "recorder").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
    }

    private fun capture(record: AudioRecord, part: File) {
        val rate = Wav.RATE
        val second = ShortArray(rate)
        val bytes = ByteArray(rate * 2)
        var quiet = 0
        var seconds = 0
        var why = Stop.PRESSED
        val out = RandomAccessFile(part, "rw")
        try {
            out.setLength(0)
            out.write(ByteArray(Wav.HEADER))
            record.startRecording()
            while (running) {
                var filled = 0
                while (filled < rate && running) {
                    val n = record.read(second, filled, rate - filled)
                    if (n < 0) { why = Stop.FAILED; running = false; break }
                    filled += n
                }
                var sum = 0.0
                for (i in 0 until filled) {
                    val s = second[i].toInt()
                    sum += (s * s).toDouble()
                    bytes[i * 2] = (s and 0xff).toByte()
                    bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                }
                out.write(bytes, 0, filled * 2)
                seconds++
                val rms = if (filled > 0) sqrt(sum / filled) else 0.0
                quiet = if (rms < Listening.QUIET_RMS) quiet + 1 else 0
                if (quiet >= QUIET_STOP) { why = Stop.QUIET; running = false }
                if (seconds >= LONGEST) { why = Stop.LONGEST; running = false }
            }
        } finally {
            // Released in a finally, or a failed read leaves the microphone held and the
            // next press finds it busy.
            runCatching { record.stop() }
            record.release()
            out.seek(0)
            out.write(Wav.header(out.length() - Wav.HEADER))
            out.close()
            running = false
            onFinished(Dreams.finish(part), why)
        }
    }
}
