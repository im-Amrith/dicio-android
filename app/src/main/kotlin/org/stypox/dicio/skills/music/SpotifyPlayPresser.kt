package org.stypox.dicio.skills.music

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional accessibility service, only allowed to look at Spotify: after Mello opens a song in
 * Spotify, it taps Spotify's own "Play" button, since Spotify ignores play requests from other
 * apps. The user turns it on once in Android's Accessibility settings.
 */
class SpotifyPlayPresser : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Taps the Play button on the Spotify screen currently shown, @return whether it did */
    fun pressPlay(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != MusicSkill.SPOTIFY_PACKAGE) return false
        val nodes = root.findAccessibilityNodeInfosByText("Play")
        // exact "Play" buttons first, then ones like "Play Believer" or "Play artist"
        val exact = nodes.filter { label(it).equals("Play", ignoreCase = true) }
        val prefixed = nodes.filter { label(it).startsWith("Play ", ignoreCase = true) }
        for (node in exact + prefixed) {
            var clickable: AccessibilityNodeInfo? = node
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        }
        return false
    }

    private fun label(node: AccessibilityNodeInfo): String =
        (node.contentDescription ?: node.text ?: "").toString().trim()

    companion object {
        @Volatile
        var instance: SpotifyPlayPresser? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }
}
