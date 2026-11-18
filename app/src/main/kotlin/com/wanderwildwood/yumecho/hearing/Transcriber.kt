package com.wanderwildwood.yumecho.hearing

import android.content.Context
import android.content.SharedPreferences
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
    private const val TRIES = "transcriber-tries"
    private const val MOST_TRIES = 3

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
            // nothing here may light the screen at night. Taken afresh for each dream, for as
            // long as that one could take, so a long queue is not cut off by one fixed timeout.
            lock.setReferenceCounted(false)
            try {
                // Re-read each time, because one may have been deleted meanwhile. Each is tried
                // once per run: one Whisper cannot hear stays waiting, and must not be retried
                // in a loop until morning.
                val tried = mutableSetOf<String>()
                val tries = app.getSharedPreferences(TRIES, Context.MODE_PRIVATE)
                forgetTheGone(tries)
                while (true) {
                    val next = Dreams.waiting()
                        .firstOrNull { it.stamp !in tried && tries.getInt(it.stamp, 0) < MOST_TRIES }
                        ?: break
                    tried += next.stamp
                    lock.acquire(maxOf(10 * 60 * 1000L, next.seconds * 3 * 1000L))
                    // Counted before it is heard and let go after, written at once: a recording
                    // that takes the whole app down while it is heard is counted, and after
                    // [MOST_TRIES] of those it is left waiting rather than stopping the app on
                    // every start.
                    tries.edit().putInt(next.stamp, tries.getInt(next.stamp, 0) + 1).commit()
                    try {
                        hear(app, next)
                    } catch (e: Exception) {
                        // Storage full or failing: this one stays waiting for the next run.
                    } catch (e: Error) {
                        if (e is VirtualMachineError) throw e
                    }
                    tries.edit().remove(next.stamp).commit()
                }
            } catch (e: Exception) {
                // Nothing that goes wrong here may stop the app, or the night armed with it.
            } finally {
                current.value = null
                if (lock.isHeld) lock.release()
            }
        }
    }

    /** Tries that never came to an end are kept only for recordings still in the log. */
    private fun forgetTheGone(tries: SharedPreferences) {
        val here = Dreams.list.value.map { it.stamp }.toSet()
        // An empty log may be one that could not be listed: nothing is forgotten on that.
        if (here.isEmpty()) return
        val gone = tries.all.keys.filter { it !in here }
        if (gone.isNotEmpty()) tries.edit().apply { gone.forEach(::remove) }.commit()
    }

    private fun hear(context: Context, dream: Dream) {
        current.value = dream.stamp
        val file = Dreams.audio(dream)
        if (!file.exists()) return
        // A file that could not be read this time (storage, memory) stays waiting; only one that
        // was read and is not this app's sound format is taken as nothing said.
        val bytes =
            try {
                file.readBytes()
            } catch (e: Exception) {
                return
            } catch (e: OutOfMemoryError) {
                return
            }
        val samples = runCatching { Wav.samples(bytes) }.getOrNull()
        val text = when {
            samples == null -> ""
            !Listening.anythingSaid(samples) -> ""
            else -> Speech.current(context).let { (language, model) ->
                Whisper.transcribe(context.assets, Whisper.MODEL, model?.path, language, samples, THREADS)
            }
                ?.let(Listening::tidy)
                // Whisper could not run at all. Left unheard, so it is tried again next time
                // rather than being marked as silence when it was not.
                ?: return
        }
        Dreams.heard(dream, text)
    }
}
