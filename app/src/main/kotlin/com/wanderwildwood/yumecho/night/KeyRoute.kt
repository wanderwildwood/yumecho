package com.wanderwildwood.yumecho.night

import android.media.MediaRoute2Info
import android.media.MediaRoute2ProviderService
import android.media.RouteDiscoveryPreference
import android.media.RoutingSessionInfo
import android.os.Bundle
import com.wanderwildwood.yumecho.R

/**
 * A pretend speaker that only this app ever chooses, while armed.
 *
 * Android 12 and later give the volume keys to an app's media session only when the app is
 * also playing to a "route" of its own (otherwise they go to the phone's own volumes, which on
 * a phone set to vibrate move nothing at all). This is that route. Nothing is ever played to it:
 * it exists so the keys come to [ArmService].
 */
class KeyRoute : MediaRoute2ProviderService() {
    companion object {
        const val FEATURE = "com.wanderwildwood.yumecho.KEYS"
        const val ROUTE = "keys"
        private const val SESSION = "armed"
    }

    private fun route() = MediaRoute2Info.Builder(ROUTE, getString(R.string.route_keys))
        .addFeature(FEATURE)
        .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
        .setVolumeMax(10)
        .setVolume(5)
        .build()

    override fun onCreate() {
        super.onCreate()
        notifyRoutes(listOf(route()))
    }

    override fun onDiscoveryPreferenceChanged(preference: RouteDiscoveryPreference) {
        notifyRoutes(listOf(route()))
    }

    override fun onCreateSession(requestId: Long, packageName: String, routeId: String, sessionHints: Bundle?) {
        if (packageName != this.packageName || routeId != ROUTE) {
            notifyRequestFailed(requestId, REASON_ROUTE_NOT_AVAILABLE)
            return
        }
        notifySessionCreated(
            requestId,
            RoutingSessionInfo.Builder(SESSION, packageName)
                .addSelectedRoute(ROUTE)
                .setVolumeHandling(MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                .setVolumeMax(10)
                .setVolume(5)
                .build(),
        )
    }

    override fun onReleaseSession(requestId: Long, sessionId: String) = notifySessionReleased(sessionId)

    // The route's volume is never real; nothing to do.
    override fun onSetRouteVolume(requestId: Long, routeId: String, volume: Int) = Unit
    override fun onSetSessionVolume(requestId: Long, sessionId: String, volume: Int) = Unit
    override fun onSelectRoute(requestId: Long, sessionId: String, routeId: String) = Unit
    override fun onDeselectRoute(requestId: Long, sessionId: String, routeId: String) = Unit
    override fun onTransferToRoute(requestId: Long, sessionId: String, routeId: String) = Unit
}
