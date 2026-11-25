package com.wanderwildwood.yumecho.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.yumecho.R
import com.wanderwildwood.yumecho.hearing.Speech

/**
 * On the main screen, an offer to hear dreams in the phone's own language, made only when that
 * differs from the language they are heard in now. Someone who speaks Polish to a phone set to
 * Polish, and never opens Settings, otherwise gets English guesses at Polish words.
 *
 * Nothing downloads until the button is pressed. "Not now" holds for that phone language; a
 * phone later set to another language is asked again. Nothing shows when the two agree, or when
 * the phone's language is not one Whisper is offered in here.
 */
@Composable
fun LanguageOffer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Speech.init(context)
    val chosen by Speech.chosen.collectAsState()
    val declined by Speech.declined.collectAsState()
    val state by Speech.state.collectAsState()
    val phone = Speech.phoneLanguage(context) ?: return
    if (phone.code == chosen || phone.code == declined) return

    Column(modifier) {
        when (val now = state) {
            is Speech.State.Downloading -> {
                TextMMD(
                    text = stringResource(R.string.speech_downloading, Speech.named(now.language), now.percent),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButtonMMD(onClick = { Speech.cancel() }) {
                    TextMMD(text = stringResource(R.string.offer_stop))
                }
            }
            else -> {
                TextMMD(
                    text = if (now is Speech.State.Failed) {
                        stringResource(R.string.speech_failed, Speech.named(now.language))
                    } else {
                        stringResource(R.string.offer_text, Speech.named(chosen), phone.name)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ButtonMMD(onClick = { Speech.choose(context, phone.code) }, modifier = Modifier.weight(1f)) {
                        TextMMD(text = stringResource(R.string.offer_yes, phone.name))
                    }
                    OutlinedButtonMMD(onClick = { Speech.decline(phone.code) }, modifier = Modifier.weight(1f)) {
                        TextMMD(text = stringResource(R.string.offer_no))
                    }
                }
                if (phone.code != "en") {
                    Spacer(Modifier.height(8.dp))
                    TextMMD(text = stringResource(R.string.offer_note), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        DottedRule(start = 0.dp, end = 0.dp)
    }
}
