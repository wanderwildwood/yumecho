package com.wanderwildwood.yumecho.night

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * A second of zeros, looped, while the app is armed.
 *
 * It is how the volume keys reach this app with the screen off. Android sends a volume key to
 * whatever is playing media, and with nothing playing it goes to the ringer instead, where an
 * app never hears of it. Playing silence makes this app what is playing. Nothing comes out of
 * the speaker.
 */
class Silence {
    private var track: AudioTrack? = null

    fun start() {
        val rate = 8000
        val samples = ShortArray(rate)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build().apply {
                write(samples, 0, samples.size)
                setLoopPoints(0, samples.size, -1)
                play()
            }
    }

    fun stop() {
        track?.run { stop(); release() }
        track = null
    }
}
