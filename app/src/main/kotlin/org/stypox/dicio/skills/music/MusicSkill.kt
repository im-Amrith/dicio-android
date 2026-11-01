package org.stypox.dicio.skills.music

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Music

/**
 * "Play X on Spotify". Uses the standard Android "play from search" intent, which Spotify handles
 * by searching for X and starting playback right away (unlike a spotify: link, which only opens
 * the search page). Falls back to the search page if that intent isn't supported.
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

        private val TRAILING_NOISE = Regex(
            """\s+(on|in|from|with|using|via)\s+spotify\s*$|\s+(please|now|for me)\s*$"""
        )

        fun cleanQuery(raw: String?): String? = raw
            ?.trim()
            ?.replace(TRAILING_NOISE, "")
            ?.removePrefix("the song ")
            ?.removePrefix("song ")
            ?.trim()

        fun playOnSpotify(context: Context, query: String): MusicOutput.Result {
            if (!isSpotifyInstalled(context)) {
                return MusicOutput.Result.NOT_INSTALLED
            }

            val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(SPOTIFY_PACKAGE)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(playIntent)
                return MusicOutput.Result.PLAYING
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Spotify does not handle play-from-search, opening search instead", e)
            }

            val searchIntent = Intent(
                Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))
            ).apply {
                setPackage(SPOTIFY_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(searchIntent)
                MusicOutput.Result.SEARCHING
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, "Could not open Spotify", e)
                MusicOutput.Result.NOT_INSTALLED
            }
        }

        private fun isSpotifyInstalled(context: Context): Boolean =
            context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) != null
    }
}
