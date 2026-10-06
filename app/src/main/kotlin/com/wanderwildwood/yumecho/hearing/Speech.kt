package com.wanderwildwood.yumecho.hearing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The language dreams are heard in.
 *
 * English is heard by the model in the APK, which knows only English and knows it best. Any
 * other language needs Whisper's multilingual model, the same size again, so it is not in the
 * APK: choosing one downloads it once, into the app's own storage, and it is used only if it
 * is byte for byte the file this app was tested with. Choosing English again deletes it.
 *
 * This download is the only thing the app ever fetches, and nothing goes the other way.
 */
object Speech {

    /** A language Whisper's base model hears well enough to be worth offering. */
    data class Language(val code: String, val name: String)

    /** Each named in its own words, the way a language list is read by someone looking for theirs. */
    val languages = listOf(
        Language("en", "English"),
        Language("cs", "Čeština"),
        Language("da", "Dansk"),
        Language("de", "Deutsch"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("it", "Italiano"),
        Language("nl", "Nederlands"),
        Language("no", "Norsk"),
        Language("pl", "Polski"),
        Language("pt", "Português"),
        Language("fi", "Suomi"),
        Language("sv", "Svenska"),
    )

    fun named(code: String): String = languages.firstOrNull { it.code == code }?.name ?: code

    /** Where a download stands. */
    sealed interface State {
        data object Idle : State
        data class Downloading(val language: String, val percent: Int) : State
        data class Failed(val language: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _chosen = MutableStateFlow("en")

    /** The language chosen, "en" until another has been downloaded for. */
    val chosen: StateFlow<String> = _chosen

    private lateinit var app: Context
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "speech") }
    @Volatile private var cancelled = false

    private val model get() = File(File(app.filesDir, "speech"), MODEL_NAME)
    private val prefs get() = app.getSharedPreferences("speech", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _chosen.value = prefs.getString(LANGUAGE, null) ?: "en"
    }

    private fun keep(language: String) {
        prefs.edit().putString(LANGUAGE, language).apply()
        _chosen.value = language
    }

    /** The language to hear in and the model to hear with: null for the English one in the APK. */
    fun current(context: Context): Pair<String, File?> {
        init(context)
        val language = _chosen.value
        // The English model if the other one has gone missing, rather than nothing.
        val file = if (language == "en") null else model.takeIf { it.isFile }
        return if (file == null) "en" to null else language to file
    }

    fun downloaded(context: Context): Boolean {
        init(context)
        return model.isFile
    }

    /**
     * Hear dreams in [language] from now on. For anything but English the multilingual model
     * is downloaded first, unless it already is, and the choice holds once it has arrived.
     */
    fun choose(context: Context, language: String) {
        init(context)
        if (language == "en") {
            cancelled = true
            keep("en")
            _state.value = State.Idle
            worker.execute {
                model.delete()
                File(model.path + ".part").delete()
            }
            return
        }
        if (model.isFile) {
            keep(language)
            _state.value = State.Idle
            return
        }
        cancelled = false
        _state.value = State.Downloading(language, 0)
        worker.execute { download(language) }
    }

    private fun download(language: String) {
        val part = File(model.path + ".part")
        part.parentFile?.mkdirs()
        val ok = runCatching {
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
                val digest = MessageDigest.getInstance("SHA-256")
                var got = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled) error("cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            got += n
                            val percent = (got * 100 / total).toInt().coerceIn(0, 99)
                            val now = _state.value
                            if (now !is State.Downloading || now.percent != percent) {
                                _state.value = State.Downloading(language, percent)
                            }
                        }
                    }
                }
                hex(digest.digest()) == MODEL_SHA256 && part.renameTo(model)
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        if (!ok) part.delete()
        if (cancelled) return
        if (ok) {
            keep(language)
            _state.value = State.Idle
        } else {
            _state.value = State.Failed(language)
        }
    }

    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private const val LANGUAGE = "language"
    private const val MODEL_NAME = "ggml-base-q5_1.bin"
    private const val MODEL_BYTES = 59_707_625L
    private const val MODEL_SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"

    // Pinned to one commit of whisper.cpp's model repository, so the file behind it cannot change.
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/$MODEL_NAME"
}
