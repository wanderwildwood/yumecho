package com.wanderwildwood.yumecho.cloud

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.wanderwildwood.yumecho.dreams.Dream
import com.wanderwildwood.yumecho.dreams.Dreams
import com.wanderwildwood.yumecho.dreams.noteText
import com.wanderwildwood.yumecho.ui.isRealBrowser
import com.wanderwildwood.yumecho.ui.nightHeadingInFile
import com.wanderwildwood.yumecho.ui.timeAndLengthInFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

enum class SignIn { IDLE, WAITING, CHECKING }

enum class Problem {
    PLAIN_HTTP, UNREACHABLE, REFUSED, NO_BROWSER, NOT_KEPT, TIMED_OUT, BAD_LINK, OTHER_SERVER, NEEDS_ADDRESS, NEEDS_USER,
    BAD_ADDRESS, NOT_FOUND, CERTIFICATE, NOT_NEXTCLOUD, WRONG_PASSWORD, OTHER,
    /** Not a problem: a Test that worked. */
    TEST_GOOD,
}

/** What a failed typed check says, in the screen's own terms. */
fun problemFor(p: CheckProblem): Problem = when (p) {
    CheckProblem.BAD_ADDRESS -> Problem.BAD_ADDRESS
    CheckProblem.PLAIN_HTTP -> Problem.PLAIN_HTTP
    CheckProblem.NOT_FOUND -> Problem.NOT_FOUND
    CheckProblem.UNREACHABLE -> Problem.UNREACHABLE
    CheckProblem.CERTIFICATE -> Problem.CERTIFICATE
    CheckProblem.NOT_NEXTCLOUD -> Problem.NOT_NEXTCLOUD
    CheckProblem.WRONG_PASSWORD -> Problem.WRONG_PASSWORD
    CheckProblem.OTHER -> Problem.OTHER
}

/**
 * Why the typed sign-in cannot go ahead yet, or null when it can. Said on screen: a form that
 * closes without a word leaves the reader guessing.
 */
fun handProblem(typed: String, user: String, appPassword: String): Problem? = when {
    typed.isBlank() -> Problem.NEEDS_ADDRESS
    LoginFlow.isPlain(typed) -> Problem.PLAIN_HTTP
    user.isBlank() -> Problem.NEEDS_USER
    appPassword.isBlank() -> Problem.NEEDS_USER
    else -> null
}

/** What Settings shows about Nextcloud. */
data class SyncState(
    val user: String? = null,
    val server: String? = null,
    /** Sending dreams is its own switch: signing in alone sends nothing. */
    val on: Boolean = false,
    val folder: String = DEFAULT_FOLDER,
    val typed: String = "",
    val signIn: SignIn = SignIn.IDLE,
    val problem: Problem? = null,
    /** The last try to send did not get through; it is tried again at the next change. */
    val behind: Boolean = false,
    /** The account is Home's (the phone's one Nextcloud account), not one signed in here. */
    val home: Boolean = false,
    /** Home's account is chosen, but Home is signed out of it (or gone): nothing is sent. */
    val homeOut: Boolean = false,
)

const val DEFAULT_FOLDER = "Dream Log"

/**
 * Keeping dreams' words in the reader's own Nextcloud, an opt-in of its own.
 *
 * Off until the reader signs in on Nextcloud's page (Login Flow v2, which hands this phone an
 * app password of its own) AND turns the sending switch on. Only the words go, never a
 * recording, as Markdown files in one folder, over https. The app password is sealed with a key
 * in the phone's keystore ([Secrets]). With it on, a dream deleted here has its copy deleted
 * there; with it off, copies already there are left alone.
 *
 * Instead of signing in here, the reader may use the phone's one Nextcloud account, the one Home
 * holds ([HomeAccount]). Then only the choice is kept (who and the stamp last taken); the app
 * password is asked of Home on each start, before each send and on each change notice, and kept
 * in memory only. When Home signs out, or is gone, sending stops until it has the account again.
 *
 * Everything here is one worker thread, never the main one, and a failure is only logged and
 * flagged: the log is never held up by a network.
 */
object DreamSync {
    private const val TAG = "DreamSync"
    private const val SERVER = "server"
    private const val USER = "user"
    private const val PASSWORD = "password"
    private const val FOLDER = "folder"
    private const val ON = "on"
    private const val SENT = "sent:"
    /** Home's account chosen: [HomeAccount.Held.who] (server and user), and the stamp last taken. */
    private const val HOME_WHO = "home_who"
    private const val HOME_STAMP = "home_stamp"

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dream-sync") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state
    private var signingIn: Job? = null

    /** Home's account as last asked, with its password: in memory only, never written down. */
    @Volatile private var held: HomeAccount.Held? = null

    /** Counts sign-in tries, so a try that was cancelled while checking is dropped when it ends. */
    @Volatile private var attempt = 0

    /** Its own dispatcher, so cancelling a sign-in check cannot touch the calls that send dreams. */
    private val checkClient = http.newBuilder().dispatcher(okhttp3.Dispatcher()).build()

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("nextcloud_sync", Context.MODE_PRIVATE)
        _state.value = signedIn(SyncState(folder = folder()))
        scope.launch {
            askHome()
            Dreams.list.collect { if (_state.value.on) catchUp(it) }
        }
    }

    /** [current] with who is signed in, and whether sending is on, as they are now. */
    private fun signedIn(current: SyncState): SyncState {
        val who = homeWho()
        if (who != null) {
            val out = homeStamp().isEmpty()
            return current.copy(
                user = if (out) null else who.substringAfter('\n'),
                server = if (out) null else who.substringBefore('\n'),
                on = !out && held != null && prefs.getBoolean(ON, false),
                home = true,
                homeOut = out,
            )
        }
        val account = ownAccount()
        return current.copy(
            user = account?.user,
            server = account?.server,
            on = account != null && prefs.getBoolean(ON, false),
            home = false,
            homeOut = false,
        )
    }

    private fun homeWho(): String? = prefs.getString(HOME_WHO, null)?.takeIf { it.isNotEmpty() }

    private fun homeStamp(): String = prefs.getString(HOME_STAMP, null).orEmpty()

    /** The account to send with: Home's while it is chosen, otherwise the one signed in here. */
    private fun account(): Account? {
        if (homeWho() != null) {
            if (homeStamp().isEmpty()) return null
            return held?.let { Account(it.server, it.user, it.password) }
        }
        return ownAccount()
    }

    private fun ownAccount(): Account? {
        val server = prefs.getString(SERVER, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString(USER, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = Secrets.open(prefs.getString(PASSWORD, null)) ?: return null
        return Account(server, user, password)
    }

    private fun folder(): String = prefs.getString(FOLDER, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER

    /**
     * When the app opens: Home is asked again, if its account is chosen, and anything that could
     * not be sent last time is tried again.
     */
    fun syncNow() {
        if (!::app.isInitialized) return
        scope.launch {
            askHome()
            if (_state.value.on) catchUp(Dreams.list.value)
        }
    }

    // Home's account ---------------------------------------------------------------------------

    /** Home says its account changed: ask again, and send what waits if it is still there. */
    fun homeChanged() = syncNow()

    /**
     * Asks Home, if its account is chosen, and does as [HomeAccount.follow] says. On the worker
     * thread only: it asks another app.
     */
    @Synchronized
    private fun askHome() {
        val who = homeWho() ?: return
        val stamp = homeStamp()
        val answer = HomeAccount.ask(app)
        when (val step = HomeAccount.follow(who, stamp, answer)) {
            // The same account under the same stamp: the password is only needed in memory again.
            is HomeAccount.Step.Keep -> if (answer is HomeAccount.Answer.Has && stamp.isNotEmpty()) held = answer.held
            is HomeAccount.Step.Take -> {
                held = step.held
                prefs.edit().putString(HOME_STAMP, step.held.stamp).apply()
            }
            is HomeAccount.Step.Drop -> {
                held = null
                val edit = prefs.edit().putString(HOME_STAMP, "")
                // Another account in Home: the choice goes too, and a press takes the new one.
                if (!step.following) {
                    edit.remove(HOME_WHO).putBoolean(ON, false)
                    prefs.all.keys.filter { it.startsWith(SENT) }.forEach { edit.remove(it) }
                }
                edit.apply()
            }
        }
        _state.update { signedIn(it).copy(behind = if (account() == null) false else it.behind) }
    }

    /**
     * "Use the phone's Nextcloud account": Home's account takes the place of any signed in here,
     * whose app password is deleted on the server as at Sign out. Sending starts off.
     */
    fun useHome() {
        if (signingIn?.isActive == true) cancelSignIn()
        scope.launch {
            val answer = HomeAccount.ask(app) as? HomeAccount.Answer.Has ?: return@launch
            val own = ownAccount()
            held = answer.held
            forgetSent()
            prefs.edit().remove(SERVER).remove(USER).remove(PASSWORD)
                .putString(HOME_WHO, answer.held.who).putString(HOME_STAMP, answer.held.stamp)
                .putBoolean(ON, false).apply()
            _state.update { signedIn(it).copy(typed = "", problem = null, behind = false, signIn = SignIn.IDLE) }
            if (own != null) LoginFlow.revoke(own)
        }
    }

    /** "Stop using it": Home's account is let go here (Home stays signed in) and the sign-in here is empty. */
    fun stopHome() {
        scope.launch {
            held = null
            prefs.edit().clear().apply()
            _state.value = SyncState()
        }
    }

    // Sending ------------------------------------------------------------------------------------

    fun setOn(on: Boolean) {
        scope.launch {
            if (on && account() == null) return@launch
            prefs.edit().putBoolean(ON, on).apply()
            // Off forgets what was sent, so on again sends everything again, to the same names.
            if (!on) forgetSent()
            _state.update { it.copy(on = on, behind = false) }
            if (on) catchUp(Dreams.list.value)
        }
    }

    private fun forgetSent() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith(SENT) }.forEach { edit.remove(it) }
        edit.apply()
    }

    private fun sent(): Map<String, Sent> = prefs.all.filterKeys { it.startsWith(SENT) }.mapNotNull { (key, value) ->
        val text = value as? String ?: return@mapNotNull null
        val cut = text.lastIndexOf('|')
        if (cut <= 0) null else key.removePrefix(SENT) to Sent(text.substring(0, cut), text.substring(cut + 1))
    }.toMap()

    private fun render(dream: Dream): String =
        noteText(dream, nightHeadingInFile(app, dream.night), timeAndLengthInFile(app, dream))

    @Synchronized
    private fun catchUp(dreams: List<Dream>) {
        if (!_state.value.on) return
        // Home is asked before every send: it may have signed out or changed its password.
        askHome()
        if (!_state.value.on) return
        val account = account() ?: return
        try {
            sync(DreamFolder(account, folder()), dreams, sent(), ::render) { stamp, was ->
                if (was == null) prefs.edit().remove(SENT + stamp).apply()
                else prefs.edit().putString(SENT + stamp, "${was.name}|${was.hash}").apply()
            }
            _state.update { it.copy(behind = false) }
        } catch (e: Exception) {
            Log.w(TAG, "could not send; will try again at the next change", e)
            _state.update { it.copy(behind = true) }
        }
    }

    /** Another folder in the same account; the next run sends every dream there afresh. */
    fun setFolder(typed: String) {
        val clean = typed.split('/').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("/").ifEmpty { DEFAULT_FOLDER }
        scope.launch {
            if (clean == folder()) return@launch
            prefs.edit().putString(FOLDER, clean).apply()
            forgetSent()
            _state.update { it.copy(folder = clean) }
            catchUp(Dreams.list.value)
        }
    }

    // Signing in ---------------------------------------------------------------------------------

    fun setAddress(typed: String) = _state.update { it.copy(typed = typed.trim(), problem = null) }

    fun clearProblem() = _state.update { it.copy(problem = null) }

    /**
     * Nextcloud's own sign-in: its login page opens in the browser, and this asks the server every
     * two seconds whether the reader has finished there, for the twenty minutes it allows.
     */
    fun startSignIn() {
        val typed = _state.value.typed
        if (typed.isBlank() || signingIn?.isActive == true) return
        if (LoginFlow.isPlain(typed)) {
            _state.update { it.copy(problem = Problem.PLAIN_HTTP) }
            return
        }
        _state.update { it.copy(signIn = SignIn.WAITING, problem = null) }
        signingIn = scope.launch {
            val started = runCatching { LoginFlow.start(typed) }.getOrElse { e ->
                _state.update { it.copy(signIn = SignIn.IDLE, problem = if (e is Refused) Problem.REFUSED else Problem.UNREACHABLE) }
                return@launch
            }
            // The login page and the address polled for the answer are both on the server typed,
            // over https; so is the server the answer names, checked below.
            if (!LoginFlow.acceptableLoginUrl(started.loginUrl, typed) ||
                !LoginFlow.acceptableLoginUrl(started.pollUrl, typed)
            ) {
                _state.update { it.copy(signIn = SignIn.IDLE, problem = Problem.BAD_LINK) }
                return@launch
            }
            val login = Intent(Intent.ACTION_VIEW, Uri.parse(started.loginUrl))
                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Only a real browser: the system's bare WebView shell answers a web address and then crashes.
            val handler = app.packageManager.resolveActivity(login, PackageManager.MATCH_DEFAULT_ONLY)
            val opened = isRealBrowser(handler?.activityInfo?.packageName) &&
                runCatching { app.startActivity(login) }.isSuccess
            if (!opened) {
                _state.update { it.copy(signIn = SignIn.IDLE, problem = Problem.NO_BROWSER) }
                return@launch
            }
            repeat(600) {
                delay(2_000)
                val got = runCatching { LoginFlow.poll(started) }.getOrElse { e ->
                    if (e is Refused) {
                        _state.update { it.copy(signIn = SignIn.IDLE, problem = Problem.REFUSED) }
                        return@launch
                    }
                    null
                }
                if (got != null) {
                    if (!LoginFlow.acceptableLoginUrl(got.server, typed)) {
                        // The app password the server has just made is not kept, so it is taken
                        // back (best effort) from the server typed, where it was handed over.
                        LoginFlow.revoke(Account(LoginFlow.normalise(typed), got.user, got.password))
                        _state.update { it.copy(signIn = SignIn.IDLE, problem = Problem.OTHER_SERVER) }
                        return@launch
                    }
                    keep(got)
                    return@launch
                }
            }
            _state.update { it.copy(signIn = SignIn.IDLE, problem = Problem.TIMED_OUT) }
        }
    }

    fun cancelSignIn() {
        attempt++
        signingIn?.cancel()
        // A check already under way stops now rather than when the server answers or gives up.
        checkClient.dispatcher.cancelAll()
        _state.update { it.copy(signIn = SignIn.IDLE) }
    }

    /**
     * The typed sign-in, the first choice: the address already typed, a login name and an app
     * password made on the server. The server is asked once; with [keepIt] false that is all (the
     * Test button), otherwise a good answer goes on to be kept.
     */
    fun signInTyped(user: String, appPassword: String, keepIt: Boolean) {
        val typed = _state.value.typed
        val missing = handProblem(typed, user, appPassword)
        if (missing != null) {
            _state.update { it.copy(problem = missing) }
            return
        }
        if (_state.value.signIn == SignIn.WAITING) cancelSignIn()
        if (signingIn?.isActive == true) return
        val mine = ++attempt
        _state.update { it.copy(signIn = SignIn.CHECKING, problem = null) }
        signingIn = scope.launch {
            val result = NextcloudCheck.run(typed, user, appPassword, client = checkClient)
            // Cancelled by the reader meanwhile: nothing to report.
            if (mine != attempt) return@launch
            when (result) {
                is CheckResult.Good -> if (keepIt) keep(result.account) else
                    _state.update { it.copy(signIn = SignIn.IDLE, problem = Problem.TEST_GOOD) }
                is CheckResult.Bad -> _state.update { it.copy(signIn = SignIn.IDLE, problem = problemFor(result.problem)) }
            }
        }
    }

    private fun keep(account: Account) {
        val mine = attempt
        _state.update { it.copy(signIn = SignIn.CHECKING, problem = null) }
        runCatching { DreamFolder(account, folder(), checkClient).probe() }.exceptionOrNull()?.let { e ->
            // Cancelled by the reader meanwhile: nothing to report.
            if (mine != attempt) return
            _state.update { it.copy(signIn = SignIn.IDLE, problem = if (e is Refused) Problem.REFUSED else Problem.UNREACHABLE) }
            return
        }
        if (mine != attempt) return
        val sealed = Secrets.seal(account.password)
        if (sealed == null) {
            _state.update { it.copy(signIn = SignIn.IDLE, problem = Problem.NOT_KEPT) }
            return
        }
        // Signing in sends nothing: the switch is the reader's to turn on.
        forgetSent()
        held = null
        prefs.edit().remove(HOME_WHO).remove(HOME_STAMP).putString(SERVER, account.server).putString(USER, account.user)
            .putString(PASSWORD, sealed).putBoolean(ON, false).apply()
        _state.update {
            it.copy(user = account.user, server = account.server, on = false, signIn = SignIn.IDLE, typed = "", behind = false)
        }
    }

    /**
     * Signed out here and on the server: the app password is deleted there first (best effort),
     * then forgotten here. Copies of dreams already in Nextcloud stay.
     */
    fun signOut() {
        scope.launch {
            val account = ownAccount()
            prefs.edit().clear().apply()
            _state.value = SyncState()
            if (account != null) LoginFlow.revoke(account)
        }
    }
}
