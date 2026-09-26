package com.wanderwildwood.yumecho.dreams

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DreamTest {
    private fun dream(stamp: String) =
        Dream(stamp, LocalDateTime.parse(stamp, Dream.STAMP), 10, null)

    @Test
    fun theSmallHoursBelongToTheNightBefore() {
        assertEquals(LocalDate.of(2026, 9, 25), dream("20260926-031204").night)
        assertEquals(LocalDate.of(2026, 9, 25), dream("20260925-231500").night)
        assertEquals(LocalDate.of(2026, 9, 25), dream("20260926-115959").night)
        assertEquals(LocalDate.of(2026, 9, 26), dream("20260926-120000").night)
    }

    @Test
    fun nightsNewestFirstDreamsInOrderWithin() {
        val grouped = byNight(
            listOf(
                dream("20260926-051000"),
                dream("20260925-013000"),
                dream("20260926-013000"),
                dream("20260925-230000"),
            ),
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 24)), grouped.map { it.first })
        assertEquals(
            listOf("20260925-230000", "20260926-013000", "20260926-051000"),
            grouped[0].second.map { it.stamp },
        )
    }
}
