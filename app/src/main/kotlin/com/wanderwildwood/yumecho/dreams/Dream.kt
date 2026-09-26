package com.wanderwildwood.yumecho.dreams

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One recording, and what was heard in it.
 *
 * [text] is null until it has been transcribed, and empty when it was, and nothing was said.
 */
data class Dream(
    val stamp: String,
    val time: LocalDateTime,
    val seconds: Int,
    val text: String?,
) {
    /** The night this belongs to: a recording at 3 a.m. on the 26th is the night of the 25th. */
    val night: LocalDate get() = nightOf(time)

    companion object {
        /** Recordings are named for when they began, so the name alone sorts and dates them. */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun nightOf(time: LocalDateTime): LocalDate = time.minusHours(12).toLocalDate()
    }
}

/** The dreams grouped by night, newest night first and in the order they came within one. */
fun byNight(dreams: List<Dream>): List<Pair<LocalDate, List<Dream>>> =
    dreams.groupBy { it.night }
        .toSortedMap(compareByDescending { it })
        .map { (night, list) -> night to list.sortedBy { it.time } }
