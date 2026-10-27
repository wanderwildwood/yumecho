package com.wanderwildwood.yumecho.night

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRoute2Info
import android.media.MediaRouter2
import android.media.RouteDiscoveryPreference
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.wanderwildwood.yumecho.MainActivity
import com.wanderwildwood.yumecho.R
import com.wanderwildwood.yumecho.dreams.Dreams
import com.wanderwildwood.yumecho.notes.InNotes
import com.wanderwildwood.yumecho.hearing.Transcriber

/**
 * Armed: waiting for a volume key, all night, with the screen off and the phone locked.
 *
 * While armed the app holds a media session that says it is playing to a remote device whose
 * volume it looks after itself, and points its media at a pretend speaker of its own
 * ([KeyRoute]: Android 12+ only lets a remote session have the keys when the app has such a
 * route). With nothing else playing, Android hands the volume keys to that session instead of moving any of the phone's own volumes, screen on or off, whatever the
 * ringer is set to. So a press reaches [onAdjustVolume] directly, no sound is played, and the
 * ringer, notification and media volumes are never touched. (Earlier versions listened for a
 * volume changing; on a phone set to vibrate the keys move nothing, so nothing was heard.)
 *
 * One buzz means it is recording; two mean it has stopped. That is the whole of the feedback,
 * on purpose: nothing here turns the screen on, lights it, or unlocks the phone, and the only
 * wake lock held is the partial one that keeps the processor awake while it records.
 *
 * Started from the app while it is open, which is what lets it keep using the microphone once
 * the phone is locked. It disarms itself after [LONGEST_NIGHT], so a morning spent forgetting
 * about it does not leave it armed all day.
 */
class ArmService : Service() {
    companion object {
        private const val LONGEST_NIGHT = 12 * 60 * 60 * 1000L
        private const val DEBOUNCE = 1500L

        fun arm(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ArmService::class.java))

        fun disarm(context: Context) {
            context.stopService(Intent(context, ArmService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var recorder: Recorder
    private var session: MediaSession? = null
    private var router: MediaRouter2? = null
    private var armed = false
    private var lastPress = 0L

    private val expire = Runnable { stopSelf() }

    /**
     * The volume the keys move while armed: a pretend one, held in the middle so a press is
     * never at an end of the range. Only the press matters, never the number.
     */
    private val keys = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 10, 5) {
        override fun onAdjustVolume(direction: Int) {
            // Android also calls this with 0 as the key comes up; only a press counts.
            if (direction != 0) main.post(::pressed)
        }
    }

    /** Moves this app's media to [KeyRoute] as soon as the system lists it. */
    private val routeWatch = object : MediaRouter2.RouteCallback() {
        // Android 12 and 13 call these two; 14 and later call onRoutesUpdated.
        @Deprecated("Android 14 calls onRoutesUpdated")
        override fun onRoutesAdded(routes: List<MediaRoute2Info>) = takeTheRoute()
        @Deprecated("Android 14 calls onRoutesUpdated")
        override fun onRoutesChanged(routes: List<MediaRoute2Info>) = takeTheRoute()
        override fun onRoutesUpdated(routes: List<MediaRoute2Info>) = takeTheRoute()
    }

    /** If Android lets the route go in the night, it is taken again. */
    private val transfers = object : MediaRouter2.TransferCallback() {
        override fun onStop(controller: MediaRouter2.RoutingController) {
            main.postDelayed(::takeTheRoute, 1000)
            main.postDelayed(nudge, 4000)
        }
    }

    private fun onTheRoute() = router?.controllers.orEmpty()
        .any { c -> c.selectedRoutes.any { it.features.contains(KeyRoute.FEATURE) } }

    /**
     * After the app has been force-stopped (by hand, or by a background manager such as
     * DuraSpeed) Android 12 does not reconnect to [KeyRoute] by itself, so the route never
     * appears. Switching the route off and on again makes Android look for it afresh.
     */
    private val nudge = Runnable {
        if (!armed || onTheRoute()) return@Runnable
        val route = ComponentName(this, KeyRoute::class.java)
        packageManager.setComponentEnabledSetting(
            route, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
        )
        packageManager.setComponentEnabledSetting(
            route, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP,
        )
    }

    private fun takeTheRoute() {
        val router = router ?: return
        if (onTheRoute()) return
        router.routes.firstOrNull { it.features.contains(KeyRoute.FEATURE) }?.let(router::transferTo)
    }

    private fun holdTheKeys() {
        router = MediaRouter2.getInstance(this).apply {
            registerRouteCallback(
                { main.post(it) }, routeWatch,
                RouteDiscoveryPreference.Builder(listOf(KeyRoute.FEATURE), true).build(),
            )
            registerTransferCallback({ main.post(it) }, transfers)
        }
        takeTheRoute()
        main.postDelayed(nudge, 3000)
        session = MediaSession(this, "yumecho:armed").apply {
            // Android hands a key press to the volume below through the session's callback
            // thread, and drops it when there is no callback, so an empty one is set.
            setCallback(object : MediaSession.Callback() {}, main)
            setPlaybackToRemote(keys)
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 0f)
                    .build(),
            )
            isActive = true
        }
    }

    private fun letGoOfTheKeys() {
        router?.run {
            unregisterTransferCallback(transfers)
            unregisterRouteCallback(routeWatch)
            stop()
        }
        router = null
        session?.run { isActive = false; release() }
        session = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Dreams.init(this)
        InNotes.init(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yumecho:recording")
        recorder = Recorder { _, _ ->
            main.post {
                if (wakeLock.isHeld) wakeLock.release()
                if (armed) Night.current.value = Night.State.ARMED
                buzz(twice = true)
                Transcriber.catchUp(this)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        if (armed) return START_NOT_STICKY
        armed = true

        holdTheKeys()
        main.postDelayed(expire, LONGEST_NIGHT)
        Night.current.value = Night.State.ARMED
        // Not sticky: if Android kills it, it stays off rather than coming back without the
        // microphone, which a restart from the background would not be allowed to use.
        return START_NOT_STICKY
    }

    private fun pressed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPress < DEBOUNCE) return
        lastPress = now
        if (recorder.isRecording) {
            recorder.stop()
        } else {
            wakeLock.acquire(Recorder.LONGEST * 1000L + 10_000)
            if (recorder.start()) {
                Night.current.value = Night.State.RECORDING
                buzz(twice = false)
            } else {
                wakeLock.release()
            }
        }
    }

    private fun buzz(twice: Boolean) {
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        val pattern = if (twice) longArrayOf(0, 120, 150, 120) else longArrayOf(0, 200)
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (armed) {
            letGoOfTheKeys()
            if (recorder.isRecording) recorder.stop()
        }
        if (wakeLock.isHeld) wakeLock.release()
        armed = false
        Night.current.value = Night.State.RESTING
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("armed", getString(R.string.channel_armed), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, "armed")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_armed))
            .setContentText(getString(R.string.notification_armed_how))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
