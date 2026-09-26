package com.wanderwildwood.yumecho

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.yumecho.dreams.Dreams
import com.wanderwildwood.yumecho.hearing.Transcriber
import com.wanderwildwood.yumecho.night.ArmService
import com.wanderwildwood.yumecho.night.Night
import com.wanderwildwood.yumecho.ui.AboutDialog
import com.wanderwildwood.yumecho.ui.DreamScreen
import com.wanderwildwood.yumecho.ui.LogScreen
import com.wanderwildwood.yumecho.ui.monochrome

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dreams.init(this)
        setContent {
            ThemeMMD(colorScheme = monochrome) {
                DreamLog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Anything left unheard, because the phone was switched off or the app was stopped
        // partway, is picked up whenever the app is opened.
        Dreams.refresh()
        Transcriber.catchUp(this)
    }
}

@Composable
private fun DreamLog() {
    val context = LocalContext.current
    val night by Night.state.collectAsStateWithLifecycle()
    val dreams by Dreams.list.collectAsStateWithLifecycle()
    val hearing by Transcriber.hearing.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    var aboutOpen by remember { mutableStateOf(false) }
    var micAllowed by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAllowed = granted
        if (granted) ArmService.arm(context)
    }

    val dream = dreams.firstOrNull { it.stamp == open }
    if (dream != null) {
        BackHandler { open = null }
        DreamScreen(
            dream = dream,
            hearing = hearing,
            onClose = { open = null },
            onDelete = {
                Dreams.delete(dream)
                open = null
            },
        )
    } else {
        LogScreen(
            night = night,
            dreams = dreams,
            hearing = hearing,
            micAllowed = micAllowed,
            onArm = {
                if (micAllowed) ArmService.arm(context) else ask.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDisarm = { ArmService.disarm(context) },
            onOpen = { open = it.stamp },
            onAbout = { aboutOpen = true },
        )
    }

    if (aboutOpen) AboutDialog(onDismiss = { aboutOpen = false })
}
