package com.wanderwildwood.yumecho.night

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * DuraSpeed, MediaTek's background manager on the Kompakt, force-stops installed apps a few
 * minutes after the screen goes dark -- and an armed night is nothing but this app waiting in
 * the dark. Stopped, it hears no press, and the morning finds it disarmed. Apps switched on in
 * DuraSpeed's list are left alone, but that list cannot be read by another app, and Settings
 * has no way into it. Its App info page can be opened, and has an Open button, so that is
 * where the button goes; whether it was done is the person's word.
 *
 * A system force-stop while DuraSpeed is on takes that word back: DuraSpeed does not stop apps
 * on its list. Android records the stop as "stop <package> due to from pid N"; a Force stop by
 * hand reads the same and is the one case this gets wrong.
 */
object DuraSpeed {
    private const val PACKAGE = "com.mediatek.duraspeed"

    /**
     * DuraSpeed is on this phone. Asked of the package itself, not the maker's name: a Kompakt
     * running another system (KompaktOS) still says Mudita and has no DuraSpeed.
     */
    fun present(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** DuraSpeed's own switch; null where the phone does not say. */
    fun isOn(context: Context): Boolean? = runCatching {
        val cr = context.contentResolver
        (Settings.Global.getString(cr, "setting.duraspeed.enabled")
            ?: Settings.System.getString(cr, "setting.duraspeed.enabled"))?.let { it != "0" }
    }.getOrNull()

    /**
     * Whether an armed night may be cut short: the Settings rows show while this is so. DuraSpeed's
     * list of apps cannot be read, so Dream Log being on it cannot be checked, and the hint
     * shows whenever DuraSpeed is on this phone and not switched off.
     */
    fun atRisk(context: Context): Boolean = present(context) && isOn(context) != false

    fun appInfo(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$PACKAGE"))
}
