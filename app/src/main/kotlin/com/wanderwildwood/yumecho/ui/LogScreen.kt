package com.wanderwildwood.yumecho.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.yumecho.R
import com.wanderwildwood.yumecho.dreams.Dream
import com.wanderwildwood.yumecho.dreams.byNight
import com.wanderwildwood.yumecho.night.Night

/**
 * The whole app: arm it at night, read it in the morning.
 *
 * There are no settings. How long it waits in silence before it stops, and how long a night
 * lasts, were each set once to what a person half asleep needs, and a screen of knobs for
 * them would be read by nobody at three in the morning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    night: Night.State,
    dreams: List<Dream>,
    hearing: String?,
    micAllowed: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onOpen: (Dream) -> Unit,
    onAbout: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.log_title)) },
                actions = { BarButton(Icons.Info, stringResource(R.string.log_cd_about), onAbout) },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Arming(night, micAllowed, onArm, onDisarm)
            Spacer(Modifier.height(16.dp))
            HorizontalDividerMMD()

            if (dreams.isEmpty()) {
                Spacer(Modifier.height(16.dp))
                TextMMD(text = stringResource(R.string.log_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumnMMD(modifier = Modifier.fillMaxSize()) {
                    for ((date, list) in byNight(dreams)) {
                        item(key = "night-$date") {
                            TextMMD(
                                text = nightHeading(date),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                        for (dream in list) {
                            item(key = dream.stamp) { DreamRow(dream, hearing, onOpen) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Arming(night: Night.State, micAllowed: Boolean, onArm: () -> Unit, onDisarm: () -> Unit) {
    when (night) {
        Night.State.RESTING -> {
            ButtonMMD(onClick = onArm, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                TextMMD(text = stringResource(if (micAllowed) R.string.log_arm else R.string.log_allow))
            }
            Spacer(Modifier.height(10.dp))
            TextMMD(
                text = stringResource(if (micAllowed) R.string.log_how else R.string.log_mic_why),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Night.State.ARMED, Night.State.RECORDING -> {
            TextMMD(
                text = stringResource(if (night == Night.State.RECORDING) R.string.log_recording else R.string.log_armed),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButtonMMD(onClick = onDisarm, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                TextMMD(text = stringResource(R.string.log_disarm))
            }
        }
    }
}

@Composable
private fun DreamRow(dream: Dream, hearing: String?, onOpen: (Dream) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(dream) }
            .padding(vertical = 10.dp),
    ) {
        TextMMD(text = timeAndLength(dream), style = MaterialTheme.typography.labelSmall)
        TextMMD(
            text = words(dream, hearing),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The words, or what is known about them yet. */
@Composable
fun words(dream: Dream, hearing: String?): String = when {
    dream.text == null && dream.stamp == hearing -> stringResource(R.string.dream_hearing)
    dream.text == null -> stringResource(R.string.dream_waiting)
    dream.text.isEmpty() -> stringResource(R.string.dream_nothing)
    else -> dream.text
}

@Composable
fun BarButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}
