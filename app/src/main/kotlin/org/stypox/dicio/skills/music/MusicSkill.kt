package org.stypox.dicio.skills.music

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import kotlinx.coroutines.delay
import org.stypox.dicio.sentences.Sentences.Music
import org.stypox.dicio.skills.notify.NotifyHandler

/**
 * "Play X on Spotify": makes Spotify search for X and start playing it, see [playOnSpotify].
 */
class MusicSkill(correspondingSkillInfo: SkillInfo, data: StandardRecognizerData<Music>)
    : StandardRecognizerSkill<Music>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Music): SkillOutput {
        val query = when (inputData) {
            is Music.Query -> cleanQuery(inputData.query)
        }
        if (query.isNullOrBlank()) {
            return MusicOutput(MusicOutput.Result.NO_QUERY, null)
        }
        return MusicOutput(playOnSpotify(ctx.android, query), query)
    }

    companion object {
        private val TAG = MusicSkill::class.simpleName
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val SESSION_WAIT_MILLIS = 8000L
        private const val PLAY_WAIT_MILLIS = 5000L

        private val TRAILING_NOISE = Regex(
            """\s+(on|in|from|with|using|via)\s+spotify\s*$|\s+(please|now|for me)\s*$"""
        )

        fun cleanQuery(raw: String?): String? = raw
            ?.trim()
            ?.replace(TRAILING_NOISE, "")
            ?.removePrefix("the song ")
            ?.removePrefix("song ")
            ?.trim()

        /**
         * Gets Spotify to actually play [query]. Spotify ignores "play" requests sent as plain
         * intents (it only shows the search page), so this talks to Spotify's media session
         * directly, like Google Assistant and Android Auto do: open Spotify if needed, wait for
         * its media session, then ask it to play from search.
         */
        suspend fun playOnSpotify(context: Context, query: String): MusicOutput.Result {
            if (!isSpotifyInstalled(context)) {
                return MusicOutput.Result.NOT_INSTALLED
            }

            var controller = spotifyController(context)
            if (controller == null) {
                // Spotify isn't running: start it and wait for its media session to appear
                context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
                val deadline = System.currentTimeMillis() + SESSION_WAIT_MILLIS
                while (controller == null && System.currentTimeMillis() < deadline) {
                    delay(300)
                    controller = spotifyController(context)
                }
            }

            if (controller != null) {
                controller.transportControls.playFromSearch(query, Bundle())
                // give Spotify a moment to search and start, then check it's really playing
                val deadline = System.currentTimeMillis() + PLAY_WAIT_MILLIS
                while (System.currentTimeMillis() < deadline) {
                    delay(300)
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        return MusicOutput.Result.PLAYING
                    }
                }
                Log.w(TAG, "Spotify did not start playing after playFromSearch")
            } else {
                Log.w(TAG, "No Spotify media session (is notification access granted?)")
            }

            // last resort: the play-from-search intent, which at least opens the search
            val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(SPOTIFY_PACKAGE)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(playIntent)
                MusicOutput.Result.SEARCHING
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Spotify does not handle play-from-search, opening search page", e)
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query)))
                            .setPackage(SPOTIFY_PACKAGE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    MusicOutput.Result.SEARCHING
                } catch (e2: ActivityNotFoundException) {
                    MusicOutput.Result.NOT_INSTALLED
                }
            }
        }

        private fun spotifyController(context: Context): MediaController? = try {
            context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, NotifyHandler::class.java))
                ?.firstOrNull { it.packageName == SPOTIFY_PACKAGE }
        } catch (e: SecurityException) {
            null // notification access not granted
        }

        private fun isSpotifyInstalled(context: Context): Boolean =
            context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) != null
    }
}
