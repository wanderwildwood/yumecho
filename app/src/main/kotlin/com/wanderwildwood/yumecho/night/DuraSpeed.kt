package com.wanderwildwood.yumecho.night

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    fun isKompakt(): Boolean = Build.MANUFACTURER.equals("Mudita", ignoreCase = true)

    /** DuraSpeed's own switch; null where the phone does not say. */
    fun isOn(context: Context): Boolean? = runCatching {
        val cr = context.contentResolver
        (Settings.Global.getString(cr, "setting.duraspeed.enabled")
            ?: Settings.System.getString(cr, "setting.duraspeed.enabled"))?.let { it != "0" }
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences("duraspeed", Context.MODE_PRIVATE)

    /** The person has said this app is switched on in DuraSpeed's list. */
    fun allowed(context: Context) {
        prefs(context).edit().putBoolean("allowed", true).apply()
    }

    /** Whether an armed night may be cut short: the Settings rows show while this is so. */
    fun atRisk(context: Context): Boolean {
        if (!isKompakt()) return false
        val on = isOn(context) != false
        val prefs = prefs(context)
        val am = context.getSystemService(ActivityManager::class.java)
        val stop = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 0) }
            .getOrDefault(emptyList())
            .filter {
                it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                    it.description?.contains("due to from pid") == true
            }
            .maxOfOrNull { it.timestamp }
        if (stop != null && stop > prefs.getLong("stopSeen", 0L)) {
            prefs.edit().putLong("stopSeen", stop).apply()
            // Only a stop while DuraSpeed is on is DuraSpeed's.
            if (on) prefs.edit().putBoolean("allowed", false).apply()
        }
        return on && !prefs.getBoolean("allowed", false)
    }

    fun appInfo(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$PACKAGE"))
}
