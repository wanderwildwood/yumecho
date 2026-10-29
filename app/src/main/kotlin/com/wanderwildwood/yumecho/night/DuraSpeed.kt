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

    private const val PREFS = "duraspeed"
    private const val ALLOWED = "allowed"
    private const val STOP_SEEN = "stop_seen"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether an armed night may be cut short, so the Settings hint shows. It hides only when
     * DuraSpeed is not on this phone, is switched off, or the person said Dream Log is on its
     * list and nothing has stopped the app since. The list cannot be read, so the word is all
     * there is to go on; a stop by the system while DuraSpeed is on takes the word back.
     */
    fun atRisk(context: Context): Boolean {
        if (!present(context)) return false
        val on = isOn(context) != false
        val prefs = prefs(context)
        val stop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                context.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(context.packageName, 0, 0)
            }.getOrDefault(emptyList())
                .filter {
                    it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                        it.description?.contains("due to from pid") == true
                }
                .maxOfOrNull { it.timestamp }
        } else {
            null
        }
        if (stop != null && stop > prefs.getLong(STOP_SEEN, 0)) {
            prefs.edit().putLong(STOP_SEEN, stop).apply()
            // Only a stop while DuraSpeed is on is DuraSpeed's.
            if (on) prefs.edit().putBoolean(ALLOWED, false).apply()
        }
        return on && !prefs.getBoolean(ALLOWED, false)
    }

    /** The person's word that Dream Log is switched on in DuraSpeed's list. */
    fun markAllowed(context: Context) {
        prefs(context).edit().putBoolean(ALLOWED, true).apply()
    }

    fun appInfo(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$PACKAGE"))
}
