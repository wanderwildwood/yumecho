package com.wanderwildwood.yumecho.night

import java.io.File

/**
 * The whole of what the app says in the dark, as patterns for the vibrator: waits and buzzes in
 * milliseconds, starting with a wait. One short buzz is recording, two are stopped, and one long
 * one is a press that left nothing recorded (the microphone would not start, or failed at once),
 * so a lost dream is known that night and not only in the morning.
 */
enum class Buzz(val pattern: LongArray) {
    RECORDING(longArrayOf(0, 200)),
    STOPPED(longArrayOf(0, 120, 150, 120)),
    NOTHING(longArrayOf(0, 600));

    companion object {
        /** What a recording ending says: [Recorder] hands over no file when nothing was kept. */
        fun afterRecording(kept: File?): Buzz = if (kept == null) NOTHING else STOPPED
    }
}
