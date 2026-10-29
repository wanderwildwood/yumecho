package com.wanderwildwood.yumecho.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.yumecho.BuildConfig
import com.wanderwildwood.yumecho.R

/**
 * What this is, what it does with what it hears, and where the source lives.
 *
 * The line about where recordings go is here because a stranger cannot safely assume the
 * answer: nearly every app that turns speech into text sends the speech to a server to do it,
 * and this one records you in bed.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val screen = LocalConfiguration.current.screenHeightDp
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(
            text = stringResource(R.string.about_title, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        // More than a dialog holds, so it turns pages like any list, with Close under it.
        PagedColumn(Modifier.fillMaxWidth().heightIn(max = (screen * 0.6f).dp)) {
            item { Section(R.string.about_privacy_title, R.string.about_privacy) }
            item { Paragraph(R.string.about_privacy_net) }
            item { Paragraph(R.string.about_privacy_rest) }
            item { Section(R.string.about_terms_title, R.string.about_terms) }
            for (line in listOf(
                R.string.about_licence, R.string.about_whisper, R.string.about_libraries, R.string.about_icons,
            )) {
                item(key = line) {
                    TextMMD(
                        text = stringResource(line),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            item { TextMMD(text = "github.com/wanderwildwood/yumecho", style = MaterialTheme.typography.bodyMedium) }
            item {
                Spacer(Modifier.height(8.dp))
                Llama()
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Paragraph(body: Int) {
    TextMMD(text = stringResource(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Section(title: Int, body: Int) {
    TextMMD(
        text = stringResource(title),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    TextMMD(text = stringResource(body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
}

/**
 * A llama at the foot of the About, which opens the page a donation goes to. The site's
 * address sits at the start of the same line and opens the site; the llama and its words
 * open the page.
 *
 * Straight to the checkout: the Donate button on the site only leads there anyway. The short
 * square.link form, which is what the site itself links to, so a regenerated checkout follows
 * it. A phone with nothing that opens a web address says so rather than doing nothing.
 */
@Composable
private fun Llama() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        TextMMD(
            text = "wanderthe.dev",
            style = MaterialTheme.typography.bodyMedium,
            // The site's address opens the site, the way the llama beside it opens its page.
            modifier = Modifier
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://wanderthe.dev")),
                        )
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 4.dp),
        )
        Spacer(Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://square.link/u/AGu8oT10")),
                        )
                    }.onFailure {
                        Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(vertical = 4.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.llama),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            TextMMD(text = stringResource(R.string.about_feed_the_llamas), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
