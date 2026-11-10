package org.stypox.dicio.skills.music

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Optional accessibility service ("Jarvis app control"), only allowed to look at Spotify and
 * Instagram: it taps Spotify's own "Play" button (Spotify ignores play requests from other apps)
 * and swipes/taps Instagram reels on command. The user turns it on once in Accessibility settings.
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

    /** The app currently on screen, e.g. "com.instagram.android" */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Swipes vertically through the middle of the screen, from/to as fractions of its height */
    suspend fun swipeVertical(fromFraction: Float, toFraction: Float): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * fromFraction)
            lineTo(x, metrics.heightPixels * toFraction)
        }
        return perform(GestureDescription.StrokeDescription(path, 0, SWIPE_MILLIS))
    }

    /** Taps the middle of the screen (on a reel: pause / play) */
    suspend fun tapCenter(): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply { moveTo(metrics.widthPixels / 2f, metrics.heightPixels * 0.45f) }
        return perform(GestureDescription.StrokeDescription(path, 0, TAP_MILLIS))
    }

    private suspend fun perform(stroke: GestureDescription.StrokeDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val accepted = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!accepted && cont.isActive) cont.resume(false)
        }

    /**
     * Moves the progress bar of the video on screen by [seconds] (negative = back), when the app
     * exposes its progress bar to accessibility. @return whether it did
     */
    fun seekBy(seconds: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val bar = findProgressBar(root) ?: return false
        val info = bar.rangeInfo ?: return false
        val span = info.max - info.min
        // progress bars count in milliseconds or seconds; anything else can't be mapped to seconds
        val step = when {
            span >= 1000f -> seconds * 1000f
            span >= 5f && info.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT -> seconds.toFloat()
            else -> return false
        }
        val target = (info.current + step).coerceIn(info.min, info.max)
        val args = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target)
        }
        return bar.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args
        )
    }

    private fun findProgressBar(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.rangeInfo != null) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findProgressBar(it) }
            if (found != null) return found
        }
        return null
    }

    private fun label(node: AccessibilityNodeInfo): String =
        (node.contentDescription ?: node.text ?: "").toString().trim()

    companion object {
        @Volatile
        var instance: SpotifyPlayPresser? = null
            private set

        val isEnabled: Boolean get() = instance != null

        private const val SWIPE_MILLIS = 280L
        private const val TAP_MILLIS = 60L
    }
}
