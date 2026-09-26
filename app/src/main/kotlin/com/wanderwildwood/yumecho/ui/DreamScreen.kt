package com.wanderwildwood.yumecho.ui

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.yumecho.R
import com.wanderwildwood.yumecho.dreams.Dream
import com.wanderwildwood.yumecho.dreams.Dreams
import kotlinx.coroutines.delay

/**
 * One dream: what the phone heard, the recording itself, and a way to be rid of it.
 *
 * The words are set out a few sentences to a paragraph, one paragraph to an item, so a long
 * dream steps through the list a paragraph at a time rather than as one item taller than the
 * screen. The title is the time, because the night and the length are too long for the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DreamScreen(dream: Dream, hearing: String?, onClose: () -> Unit, onDelete: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = clock(dream)) },
                navigationIcon = { BarButton(Icons.Close, stringResource(R.string.dream_cd_close), onClose) },
            )
        },
    ) { contentPadding ->
        LazyColumnMMD(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 20.dp),
        ) {
            item {
                TextMMD(
                    text = nightAndLength(dream),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
            }
            val text = dream.text
            if (text.isNullOrEmpty()) {
                item { TextMMD(text = words(dream, hearing), style = MaterialTheme.typography.bodyLarge) }
            } else {
                for (paragraph in paragraphs(text)) {
                    item {
                        TextMMD(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }
                item {
                    TextMMD(
                        text = stringResource(R.string.dream_mishears),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            item {
                Spacer(Modifier.height(20.dp))
                Playback(dream)
                Spacer(Modifier.height(20.dp))
                HorizontalDividerMMD()
            }
            item { DeleteRow(onDelete) }
        }
    }
}

/** Whisper's text, three sentences to a paragraph. It has no paragraphs of its own. */
private fun paragraphs(text: String): List<String> =
    text.split(Regex("(?<=[.!?])\\s+"))
        .filter { it.isNotBlank() }
        .chunked(3) { it.joinToString(" ") }

@Composable
private fun Playback(dream: Dream) {
    var playing by remember { mutableStateOf(false) }
    val player = remember(dream.stamp) { MediaPlayer() }
    DisposableEffect(player) {
        runCatching {
            player.setDataSource(Dreams.audio(dream).path)
            player.prepare()
        }
        player.setOnCompletionListener { playing = false }
        onDispose { player.release() }
    }
    OutlinedButtonMMD(
        onClick = {
            runCatching {
                if (playing) player.pause() else player.start()
                playing = !playing
            }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (playing) Icons.Pause else Icons.Play,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextMMD(text = stringResource(if (playing) R.string.dream_pause else R.string.dream_play))
        }
    }
}

/**
 * Asks in its own face, and forgets it asked after four seconds, so a stray tap does not leave
 * a live delete for whoever picks the phone up next.
 */
@Composable
private fun DeleteRow(onDelete: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    TextMMD(
        text = stringResource(if (armed) R.string.dream_delete_confirm else R.string.dream_delete),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (armed) onDelete() else armed = true }
            .padding(vertical = 16.dp),
    )
}
