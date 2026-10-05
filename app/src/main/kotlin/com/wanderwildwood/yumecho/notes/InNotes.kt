package com.wanderwildwood.yumecho.notes

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.wanderwildwood.yumecho.dreams.Dream
import com.wanderwildwood.yumecho.dreams.Dreams
import com.wanderwildwood.yumecho.dreams.noteText
import com.wanderwildwood.yumecho.dreams.notePath
import com.wanderwildwood.yumecho.dreams.worthANote
import com.wanderwildwood.yumecho.ui.nightHeadingInFile
import com.wanderwildwood.yumecho.ui.timeAndLengthInFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * "Keep dreams in Notes": each dream's words also kept as a Markdown note in Notes, in a
 * Dreams folder, so they sync wherever Notes syncs and turn up in its search.
 *
 * Notes takes them through a small provider of its own, which answers only to the released
 * Dream Log. Dream Log itself still has no way to reach the network; Notes does that part, if
 * it has been set up to.
 *
 * It follows the log rather than being told about each change: whenever the list of dreams
 * changes, every dream with words that has no note yet gets one, and every note whose dream has
 * gone is removed. So a dream that could not be sent because Notes was busy or away is sent
 * with the next change, and nothing here ever holds up a recording or a delete. The paths it
 * has written are remembered per dream, so a note keeps its name.
 */
object InNotes {
    private const val TAG = "InNotes"
    private val PROVIDER: Uri = Uri.parse("content://com.wanderwildwood.oboegaki.capture")
    private const val ON = "on"
    private const val PATH = "path:"

    /** What Notes said when asked. */
    enum class Answer { OK, NOT_INSTALLED, NOT_SET_UP, REFUSED, FAILED }

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences

    /** One at a time, in order, and never on the main thread. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "in-notes") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)

    val on: Boolean get() = prefs.getBoolean(ON, false)

    /** After [Dreams.init], so the first list it sees is the real one and not an empty start. */
    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("in_notes", Context.MODE_PRIVATE)
        scope.launch { Dreams.list.collect { if (on) catchUp(it) } }
    }

    /**
     * Asks Notes whether it will take dreams, and if it will, turns this on and sends every
     * dream across. [answer] is called on the main thread.
     */
    fun turnOn(answer: (Answer) -> Unit) {
        scope.launch {
            val said = call("ready", null)
            if (said == Answer.OK) {
                prefs.edit().clear().putBoolean(ON, true).commit()
                catchUp(Dreams.list.value)
            }
            withContext(Dispatchers.Main) { answer(said) }
        }
    }

    /**
     * The notes already in Notes stay there. What was sent is forgotten, so turning it on again
     * sends everything again, to the same names.
     */
    fun turnOff() {
        // On the worker, so it lands after anything already on its way rather than under it.
        scope.launch { prefs.edit().clear().putBoolean(ON, false).commit() }
    }

    private fun catchUp(dreams: List<Dream>) {
        if (!on) return
        val sent = prefs.all.filterKeys { it.startsWith(PATH) }
            .mapKeys { it.key.removePrefix(PATH) }
            .mapValues { it.value as String }
        val here = dreams.mapTo(HashSet()) { it.stamp }
        for ((stamp, path) in sent) {
            if (stamp in here) continue
            val said = call("remove", path)
            if (said == Answer.OK) prefs.edit().remove(PATH + stamp).apply() else return giveUp("remove", said)
        }
        val taken = sent.values.toMutableSet()
        for (dream in dreams) {
            if (!worthANote(dream) || dream.stamp in sent) continue
            val path = notePath(dream.time, taken)
            val text = noteText(dream, nightHeadingInFile(app, dream.night), timeAndLengthInFile(app, dream))
            val said = call("put", path, Bundle().apply { putString("text", text) })
            if (said != Answer.OK) return giveUp("put", said)
            prefs.edit().putString(PATH + dream.stamp, path).apply()
            taken += path
        }
    }

    /** Left for the next change to the log to try again, rather than retried in a loop. */
    private fun giveUp(method: String, said: Answer) {
        Log.w(TAG, "$method: $said; will try again when the log next changes")
    }

    private fun call(method: String, arg: String?, extras: Bundle? = null): Answer {
        val reply = try {
            app.contentResolver.call(PROVIDER, method, arg, extras)
        } catch (e: IllegalArgumentException) {
            // "Unknown authority": Notes is not installed, or is a version without the provider.
            return Answer.NOT_INSTALLED
        } catch (e: SecurityException) {
            return Answer.REFUSED
        } catch (e: Exception) {
            Log.w(TAG, "$method failed", e)
            return Answer.FAILED
        } ?: return Answer.NOT_INSTALLED
        if (reply.getBoolean("ok", false)) return Answer.OK
        return when (reply.getString("reason")) {
            "not_set_up" -> Answer.NOT_SET_UP
            "refused" -> Answer.REFUSED
            else -> {
                Log.w(TAG, "$method: ${reply.getString("reason")} ${reply.getString("message").orEmpty()}")
                Answer.FAILED
            }
        }
    }
}
