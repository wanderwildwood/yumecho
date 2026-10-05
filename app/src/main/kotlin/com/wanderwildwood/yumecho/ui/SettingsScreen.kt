package com.wanderwildwood.yumecho.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.yumecho.R
import com.wanderwildwood.yumecho.notes.InNotes

/**
 * One setting: keeping dreams in Notes as well.
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
        }
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
