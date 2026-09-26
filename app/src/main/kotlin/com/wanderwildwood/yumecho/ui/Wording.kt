package com.wanderwildwood.yumecho.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wanderwildwood.yumecho.R
import com.wanderwildwood.yumecho.dreams.Dream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Night of Friday 25 September", in the phone's own language and order. */
@Composable
fun nightHeading(night: LocalDate): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
    return stringResource(R.string.night_of, night.format(DateTimeFormatter.ofPattern(pattern)))
}

/** "03:12 · 2 minutes", with the clock the phone is set to, 12- or 24-hour. */
@Composable
fun timeAndLength(dream: Dream): String {
    val context = LocalContext.current
    return stringResource(R.string.time_and_length, clock(context, dream), length(dream.seconds))
}

/** "3:12 AM" or "03:12", as the phone is set. */
@Composable
fun clock(dream: Dream): String = clock(LocalContext.current, dream)

/** "Night of Friday 25 September · 2 minutes" */
@Composable
fun nightAndLength(dream: Dream): String =
    stringResource(R.string.time_and_length, nightHeading(dream.night), length(dream.seconds))

private fun clock(context: Context, dream: Dream): String {
    val skeleton = if (DateFormat.is24HourFormat(context)) "Hmm" else "hmm"
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
    return dream.time.format(DateTimeFormatter.ofPattern(pattern))
}

/** Under a minute in seconds; otherwise whole minutes, rounded, since nobody needs 2:47. */
@Composable
private fun length(seconds: Int): String =
    if (seconds < 60) {
        pluralStringResource(R.plurals.duration_seconds, seconds, seconds)
    } else {
        val minutes = (seconds + 30) / 60
        pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    }

/** [length], for the saved file, which is written outside any screen. */
private fun length(context: Context, seconds: Int): String =
    if (seconds < 60) {
        context.resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds)
    } else {
        val minutes = (seconds + 30) / 60
        context.resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
    }

/** "Night of Friday 25 September 2026": the file is kept for years, so it carries the year. */
fun nightHeadingInFile(context: Context, night: LocalDate): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMMyyyy")
    return context.getString(R.string.night_of, night.format(DateTimeFormatter.ofPattern(pattern)))
}

/** "03:12 · 2 minutes", for the saved file. */
fun timeAndLengthInFile(context: Context, dream: Dream): String =
    context.getString(R.string.time_and_length, clock(context, dream), length(context, dream.seconds))
