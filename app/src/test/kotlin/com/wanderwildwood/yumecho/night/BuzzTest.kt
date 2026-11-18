package com.wanderwildwood.yumecho.night

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuzzTest {

    private fun buzzes(b: Buzz) = b.pattern.toList().drop(1).filterIndexed { i, _ -> i % 2 == 0 }

    @Test
    fun aRecordingKeptBuzzesTwice() {
        assertEquals(Buzz.STOPPED, Buzz.afterRecording(File("dream.wav")))
        assertEquals(2, buzzes(Buzz.STOPPED).size)
    }

    @Test
    fun nothingKeptIsOneLongBuzz() {
        assertEquals(Buzz.NOTHING, Buzz.afterRecording(null))
        val long = buzzes(Buzz.NOTHING)
        assertEquals(1, long.size)
        assertTrue(long.single() > 2 * buzzes(Buzz.RECORDING).single())
    }

    @Test
    fun recordingIsOneShortBuzz() {
        assertEquals(1, buzzes(Buzz.RECORDING).size)
    }
}
