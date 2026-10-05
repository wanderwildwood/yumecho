package com.wanderwildwood.yumecho.night

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
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
 * A press is heard as the media volume changing (see [Silence] for why media), and the volume
 * is put straight back. One buzz means it is recording; two mean it has stopped. That is the
 * whole of the feedback, on purpose: nothing here turns the screen on, lights it, or unlocks
 * the phone, and the only wake lock held is the partial one that keeps the processor awake
 * while it records.
 *
 * Started from the app while it is open, which is what lets it keep using the microphone once
 * the phone is locked. It disarms itself after [LONGEST_NIGHT], so a morning spent forgetting
 * about it does not leave it playing silence all day.
 */
class ArmService : Service() {
    companion object {
        private const val LONGEST_NIGHT = 12 * 60 * 60 * 1000L
        private const val DEBOUNCE = 1500L

        // Media, and the streams Android reports alongside it. The ringer is here because on a
        // phone that does not count the silence as media playing, the key goes there instead.
        // Never the alarm or a call: a volume key pressed to quiet the morning alarm must not
        // start a recording.
        private val KEY_STREAMS = setOf(
            AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC,
            9 /* tts */, 10 /* accessibility */, 11 /* assistant */,
        )

        fun arm(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ArmService::class.java))

        fun disarm(context: Context) {
            context.stopService(Intent(context, ArmService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val silence = Silence()
    private lateinit var audio: AudioManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var recorder: Recorder
    private var armed = false
    private var lastPress = 0L
    private var settlingUntil = 0L
    private var armedVolumes = emptyMap<Int, Int>()

    private val expire = Runnable { stopSelf() }

    private val keys = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            val was = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
            if (now == was || stream !in KEY_STREAMS) return
            // The reset below changes the volume too, and must not count as a press.
            if (SystemClock.elapsedRealtime() < settlingUntil) return
            main.postDelayed(::putVolumesBack, 300)
            pressed()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Dreams.init(this)
        InNotes.init(this)
        audio = getSystemService(AudioManager::class.java)
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
            1, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        if (armed) return START_NOT_STICKY
        armed = true

        // A press at either end of the range changes nothing, so nothing is heard.
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val music = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (music == 0 || music == max) audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
        armedVolumes = listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING)
            .associateWith { audio.getStreamVolume(it) }

        silence.start()
        ContextCompat.registerReceiver(
            this, keys, IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED,
        )
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

    /**
     * Every volume a press may have moved goes back to where arming left it. Without this a
     * night of presses walks the volume to the bottom, where a press changes nothing and so is
     * never heard.
     */
    private fun putVolumesBack() {
        settlingUntil = SystemClock.elapsedRealtime() + 1500
        for ((stream, volume) in armedVolumes) {
            if (audio.getStreamVolume(stream) != volume) {
                // Moving the ringer can be refused while Do Not Disturb is on. The media volume
                // is the one that matters, so a refusal here is let go.
                runCatching { audio.setStreamVolume(stream, volume, 0) }
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
            unregisterReceiver(keys)
            silence.stop()
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
