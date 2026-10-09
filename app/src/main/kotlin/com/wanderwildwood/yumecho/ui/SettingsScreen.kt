package com.wanderwildwood.yumecho.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.yumecho.R
import com.wanderwildwood.yumecho.hearing.Speech
import com.wanderwildwood.yumecho.night.DuraSpeed
import com.wanderwildwood.yumecho.notes.InNotes

/**
 * Two settings: keeping dreams in Notes as well, and the language dreams are heard in.
 *
 * Turning it on asks Notes first, and stays off if Notes says no, saying why in a dialog: a
 * switch that showed on while nothing was being kept would be the one lie on the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    var on by remember { mutableStateOf(InNotes.on) }
    // Set while Notes is being asked, so a second press does not ask twice.
    var asking by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf<InNotes.Answer?>(null) }
    val context = LocalContext.current
    remember { Speech.init(context) }
    var checks by remember { mutableStateOf(0) }
    val duraSpeedRisk = remember(checks) { DuraSpeed.atRisk(context) }
    fun open(intent: android.content.Intent) = runCatching {
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    var choosingSpeech by remember { mutableStateOf(false) }
    val speech by Speech.state.collectAsState()
    val spoken by Speech.chosen.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.settings_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.settings_cd_back), onBack) },
            )
        },
    ) { contentPadding ->
        LazyColumnMMD(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            // On a Kompakt, DuraSpeed shuts the app down in the dark, which is where an armed
            // night is spent. Its list cannot be read, so the second row is how the person says
            // Dream Log is on it. Only what is wrong is shown.
            if (duraSpeedRisk) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { open(DuraSpeed.appInfo()) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        TextMMD(text = stringResource(R.string.settings_duraspeed_open), style = MaterialTheme.typography.bodyLarge)
                        TextMMD(text = stringResource(R.string.settings_duraspeed), style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDividerMMD()
                }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                DuraSpeed.allowed(context)
                                checks++
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        TextMMD(text = stringResource(R.string.settings_duraspeed_done), style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDividerMMD()
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            when {
                                asking -> Unit
                                on -> {
                                    on = false
                                    InNotes.turnOff()
                                }
                                else -> {
                                    asking = true
                                    InNotes.turnOn { answer ->
                                        asking = false
                                        if (answer == InNotes.Answer.OK) on = true else refused = answer
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        TextMMD(text = stringResource(R.string.settings_notes), style = MaterialTheme.typography.bodyLarge)
                        TextMMD(text = stringResource(R.string.settings_notes_what), style = MaterialTheme.typography.labelSmall)
                    }
                    SwitchMMD(checked = on, onCheckedChange = null)
                }
                HorizontalDividerMMD()
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val now = speech
                            if (now is Speech.State.Failed) Speech.choose(context, now.language) else choosingSpeech = true
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    TextMMD(text = stringResource(R.string.speech_title), style = MaterialTheme.typography.bodyLarge)
                    TextMMD(
                        text = when (val now = speech) {
                            is Speech.State.Downloading -> stringResource(R.string.speech_downloading, Speech.named(now.language), now.percent)
                            is Speech.State.Failed -> stringResource(R.string.speech_failed, Speech.named(now.language))
                            Speech.State.Idle -> Speech.named(spoken)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                HorizontalDividerMMD()
            }
        }
    }

    if (choosingSpeech) {
        SpeechDialog(
            current = (speech as? Speech.State.Downloading)?.language ?: spoken,
            downloaded = Speech.downloaded(context),
            onDone = { language ->
                choosingSpeech = false
                if (language != null) Speech.choose(context, language)
            },
        )
    }

    refused?.let { answer ->
        EInkDialog(onDismiss = { refused = null }) {
            TextMMD(
                text = stringResource(
                    when (answer) {
                        InNotes.Answer.NOT_INSTALLED -> R.string.settings_notes_missing
                        InNotes.Answer.NOT_SET_UP -> R.string.settings_notes_not_set_up
                        InNotes.Answer.REFUSED -> R.string.settings_notes_refused
                        else -> R.string.settings_notes_failed
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButtonMMD(onClick = { refused = null }, modifier = Modifier.fillMaxWidth()) {
                TextMMD(text = stringResource(R.string.settings_close))
            }
        }
    }
}

/**
 * The language dreams are heard in. A language that needs the download asks first, with its
 * size, since it goes over whatever connection the phone has.
 */
@Composable
private fun SpeechDialog(current: String, downloaded: Boolean, onDone: (String?) -> Unit) {
    var asking by remember { mutableStateOf<Speech.Language?>(null) }
    EInkDialog(onDismiss = { onDone(null) }) {
        val ask = asking
        if (ask == null) {
            TextMMD(text = stringResource(R.string.speech_title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            TextMMD(text = stringResource(R.string.speech_note), style = MaterialTheme.typography.labelSmall)
            LazyColumnMMD(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                for (language in Speech.languages) {
                    item(key = language.code) {
                        TextMMD(
                            text = language.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (language.code == current) FontWeight.Bold else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (language.code == "en" || downloaded) onDone(language.code) else asking = language
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        } else {
            TextMMD(text = stringResource(R.string.speech_ask, ask.name), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            TextMMD(text = stringResource(R.string.speech_note), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(16.dp))
            ButtonMMD(onClick = { onDone(ask.code) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                TextMMD(text = stringResource(R.string.speech_download))
            }
        }
    }
}
