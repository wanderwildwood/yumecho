package com.wanderwildwood.yumecho.dreams

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class ExportTest {
    private fun dream(stamp: String, text: String?) =
        Dream(stamp, LocalDateTime.parse(stamp, Dream.STAMP), 10, text)

    private fun export(vararg dreams: Dream) = exportText(
        dreams = dreams.toList(),
        title = "Dream Log",
        nightHeading = { "Night of $it" },
        dreamHeading = { it.stamp },
        notHeard = "Not heard yet",
    )

    @Test
    fun oldestNightFirstNothingSaidLeftOutUnheardKept() {
        val text = export(
            dream("20260926-031204", " A heron. It spoke. It left. Then the sea."),
            dream("20260925-020000", ""),
            dream("20260925-040000", null),
            dream("20260924-050000", "   "),
        )
        assertEquals(
            """
            # Dream Log

            ## Night of 2026-09-24

            ### 20260925-040000

            _Not heard yet_

            ## Night of 2026-09-25

            ### 20260926-031204

            A heron. It spoke. It left.

            Then the sea.

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun aNightWithNothingSaidHasNoHeading() {
        assertEquals("# Dream Log\n", export(dream("20260926-031204", "")))
    }
}
