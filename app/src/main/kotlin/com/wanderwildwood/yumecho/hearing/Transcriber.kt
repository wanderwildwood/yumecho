package com.wanderwildwood.yumecho.hearing

import android.content.Context
import android.os.PowerManager
import com.wanderwildwood.yumecho.dreams.Dream
import com.wanderwildwood.yumecho.dreams.Dreams
import com.wanderwildwood.yumecho.dreams.Wav
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

/**
 * Turns recordings into text, one at a time, on the phone.
 *
 * On the Kompakt a minute of speech takes about a minute. That is why it happens here, in the
 * background, straight after a recording stops, rather than while you wait: by the morning it
 * has long finished. It uses three of the four cores, so a second dream can still be recorded
 * while the first is being heard.
 */
object Transcriber {
    private const val THREADS = 3

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "transcriber") }
    private val current = MutableStateFlow<String?>(null)

    /** The stamp of the recording being heard right now, if any. */
    val hearing: StateFlow<String?> = current

    /** Hear every recording that has not been heard yet. */
    fun catchUp(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val lock = app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yumecho:transcribing")
            // Held so the phone does not doze halfway through a dream. Never a screen lock:
            // nothing here may light the screen at night.
            lock.acquire(30 * 60 * 1000L)
            try {
                // Re-read each time, because one may have been deleted meanwhile. Each is tried
                // once per run: one Whisper cannot hear stays waiting, and must not be retried
                // in a loop until morning.
                val tried = mutableSetOf<String>()
                while (true) {
                    val next = Dreams.waiting().firstOrNull { it.stamp !in tried } ?: break
                    tried += next.stamp
                    hear(app, next)
                }
            } finally {
                current.value = null
                if (lock.isHeld) lock.release()
            }
        }
    }

    private fun hear(context: Context, dream: Dream) {
        current.value = dream.stamp
        val file = Dreams.audio(dream)
        if (!file.exists()) return
        val samples = runCatching { Wav.samples(file.readBytes()) }.getOrNull()
        val text = when {
            samples == null -> ""
            !Listening.anythingSaid(samples) -> ""
            else -> Whisper.transcribe(context.assets, Whisper.MODEL, samples, THREADS)
                ?.let(Listening::tidy)
                // Whisper could not run at all. Left unheard, so it is tried again next time
                // rather than being marked as silence when it was not.
                ?: return
        }
        Dreams.heard(dream, text)
    }
}
