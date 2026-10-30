package com.wanderwildwood.yumecho.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.yumecho.R
import com.wanderwildwood.yumecho.cloud.DreamSync
import com.wanderwildwood.yumecho.cloud.Problem
import com.wanderwildwood.yumecho.cloud.SignIn
import kotlinx.coroutines.delay

private enum class Entry { ADDRESS, USER, PASSWORD, FOLDER }

/**
 * Keeping dreams in the reader's own Nextcloud: what it does said first and plainly, then
 * signing in on Nextcloud's own page, the switch that actually sends, the folder, and signing
 * out. Nothing is sent until the switch is on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextcloudScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by DreamSync.state.collectAsState()
    var editing by remember { mutableStateOf<Entry?>(null) }
    var user by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBarMMD(
                title = { TextMMD(text = stringResource(R.string.nc_title)) },
                navigationIcon = { BarButton(Icons.Back, stringResource(R.string.settings_cd_back), onBack) },
            )
        },
    ) { contentPadding ->
        PagedColumn(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            item { Note(stringResource(R.string.nc_explain)) }
            item { Note(stringResource(R.string.nc_private)) }
            if (state.user != null) {
                item {
                    Choice(stringResource(R.string.nc_account), stringResource(R.string.nc_signed_in, state.user.orEmpty(), hostOf(state.server)), {})
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { DreamSync.setOn(!state.on) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            TextMMD(text = stringResource(R.string.nc_send), style = MaterialTheme.typography.bodyLarge)
                            TextMMD(text = stringResource(R.string.nc_send_what), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(16.dp))
                        SwitchMMD(checked = state.on, onCheckedChange = null)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (state.on && state.behind) item { Note(stringResource(R.string.nc_behind)) }
                item { Choice(stringResource(R.string.nc_folder), state.folder, { editing = Entry.FOLDER }) }
                item { ArmedRow(stringResource(R.string.nc_sign_out), stringResource(R.string.nc_sign_out_armed), stringResource(R.string.nc_sign_out_note)) { DreamSync.signOut() } }
            } else {
                item { Choice(stringResource(R.string.nc_address), state.typed.ifEmpty { stringResource(R.string.nc_not_set) }, { editing = Entry.ADDRESS }) }
                if (state.byHand) {
                    item { Choice(stringResource(R.string.nc_user), user.ifEmpty { stringResource(R.string.nc_not_set) }, { editing = Entry.USER }) }
                    item { Choice(stringResource(R.string.nc_app_password), stringResource(R.string.nc_app_password_note), { editing = Entry.PASSWORD }) }
                } else if (state.typed.isNotEmpty()) {
                    item {
                        Choice(
                            stringResource(
                                when (state.signIn) {
                                    SignIn.IDLE -> R.string.nc_sign_in
                                    SignIn.WAITING -> R.string.nc_waiting
                                    SignIn.CHECKING -> R.string.nc_checking
                                },
                            ),
                            stringResource(
                                when (state.signIn) {
                                    SignIn.IDLE -> R.string.nc_sign_in_note
                                    SignIn.WAITING -> R.string.nc_waiting_note
                                    SignIn.CHECKING -> R.string.nc_checking_note
                                },
                            ),
                            {
                                when (state.signIn) {
                                    SignIn.IDLE -> DreamSync.startSignIn()
                                    SignIn.WAITING -> DreamSync.cancelSignIn()
                                    SignIn.CHECKING -> DreamSync.cancelSignIn()
                                }
                            },
                        )
                    }
                }
                state.problem?.let { p ->
                    item {
                        Note(
                            stringResource(
                                when (p) {
                                    Problem.PLAIN_HTTP -> R.string.nc_plain_http
                                    Problem.UNREACHABLE -> R.string.nc_unreachable
                                    Problem.REFUSED -> R.string.nc_refused
                                    Problem.NO_BROWSER -> R.string.nc_no_browser
                                    Problem.NOT_KEPT -> R.string.nc_not_kept
                                    Problem.TIMED_OUT -> R.string.nc_timed_out
                                    Problem.BAD_LINK -> R.string.nc_bad_link
                                    Problem.NEEDS_ADDRESS -> R.string.nc_needs_address
                                    Problem.NEEDS_USER -> R.string.nc_needs_user
                                },
                            ),
                        )
                    }
                }
                if (!state.byHand) {
                    item {
                        Choice(stringResource(R.string.nc_by_hand), stringResource(R.string.nc_by_hand_note), { DreamSync.cancelSignIn(); DreamSync.byHand(true) })
                    }
                }
            }
        }
    }

    when (editing) {
        Entry.ADDRESS -> EntryDialog(
            title = stringResource(R.string.nc_address),
            note = stringResource(R.string.nc_address_note),
            initial = state.typed.ifEmpty { "https://" },
            secret = false,
            onDone = DreamSync::setAddress,
            onDismiss = { editing = null },
        )
        Entry.USER -> EntryDialog(
            title = stringResource(R.string.nc_user),
            note = stringResource(R.string.nc_user_note),
            initial = user,
            secret = false,
            onDone = { user = it.trim(); DreamSync.clearProblem() },
            onDismiss = { editing = null },
            words = true,
        )
        Entry.PASSWORD -> EntryDialog(
            title = stringResource(R.string.nc_app_password),
            note = stringResource(R.string.nc_app_password_how),
            initial = "",
            secret = true,
            onDone = { DreamSync.signInByHand(user, it) },
            onDismiss = { editing = null },
        )
        Entry.FOLDER -> EntryDialog(
            title = stringResource(R.string.nc_folder),
            note = stringResource(R.string.nc_folder_note),
            initial = state.folder,
            secret = false,
            onDone = DreamSync::setFolder,
            onDismiss = { editing = null },
            words = true,
        )
        null -> Unit
    }
}

private fun hostOf(server: String?): String = server.orEmpty().substringAfter("://").trimEnd('/')

@Composable
private fun Note(text: String) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun Choice(title: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyLarge)
        TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
    Spacer(Modifier.height(8.dp))
}

/** A row that asks for a second tap before it acts, and forgets the first after six seconds. */
@Composable
private fun ArmedRow(title: String, armedTitle: String, note: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(6_000)
            armed = false
        }
    }
    Choice(if (armed) armedTitle else title, note) {
        if (armed) {
            armed = false
            onConfirm()
        } else {
            armed = true
        }
    }
}

/**
 * Typing a server address, a name or a key. Done on the keyboard saves it: a keyboard up over
 * this panel covers the dialog's own buttons.
 */
@Composable
private fun EntryDialog(
    title: String,
    note: String,
    initial: String,
    secret: Boolean,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
    words: Boolean = false,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var shown by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val filled = value.text.isNotBlank() && value.text.trim() != "https://"
    val save = {
        if (filled) onDone(value.text)
        onDismiss()
    }
    EInkDialog(onDismiss = onDismiss) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        TextMMD(text = note, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))
        TextFieldMMD(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            textStyle = if (secret) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
            visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (secret) {
                {
                    BarButton(
                        icon = if (shown) Icons.VisibilityOff else Icons.Visibility,
                        description = stringResource(if (shown) R.string.nc_hide_key else R.string.nc_show_key),
                        onClick = { shown = !shown },
                    )
                }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = when {
                    secret -> KeyboardType.Password
                    words -> KeyboardType.Text
                    else -> KeyboardType.Uri
                },
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButtonMMD(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) {
                TextMMD(text = stringResource(R.string.nc_cancel), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(10.dp))
            if (filled) {
                OutlinedButtonMMD(onClick = save, modifier = Modifier.weight(1f).height(48.dp)) {
                    TextMMD(text = stringResource(R.string.nc_save), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
