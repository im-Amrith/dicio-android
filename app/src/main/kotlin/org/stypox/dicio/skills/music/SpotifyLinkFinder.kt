package org.stypox.dicio.skills.music

import android.net.Uri
import android.util.Log
import org.jsoup.Jsoup
import org.stypox.dicio.util.ConnectionUtils

/**
 * Finds the Spotify link for what the user asked to play (a song, artist, album or playlist),
 * by searching DuckDuckGo for open.spotify.com pages. No Spotify account or API key needed.
 */
object SpotifyLinkFinder {
    private val TAG = SpotifyLinkFinder::class.simpleName
    private val SPOTIFY_PAGE = Regex("""open\.spotify\.com/(?:intl-[a-z-]+/)?(track|artist|album|playlist)/([A-Za-z0-9]{22})""")

    /** @return a "spotify:track:..." style uri, or null if nothing was found */
    fun find(query: String): String? = try {
        val html = ConnectionUtils.getPage(
            "https://html.duckduckgo.com/html/?q=" + ConnectionUtils.urlEncode("$query site:open.spotify.com"),
            mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:143.0) Gecko/20100101 Firefox/143.0",
                "Accept" to "text/html,application/xhtml+xml",
            )
        )
        Jsoup.parse(html).select("a.result__a").asSequence()
            .mapNotNull { a ->
                val href = a.attr("href")
                // DuckDuckGo wraps the real url in its "uddg" parameter
                val url = runCatching { Uri.parse(href).getQueryParameter("uddg") }.getOrNull() ?: href
                SPOTIFY_PAGE.find(url)
            }
            // songs first: "play believer" means the song, unless only an artist page is found
            .sortedBy { if (it.groupValues[1] == "track") 0 else 1 }
            .firstOrNull()
            ?.let { "spotify:${it.groupValues[1]}:${it.groupValues[2]}" }
    } catch (e: Exception) {
        Log.w(TAG, "Could not search for a Spotify link", e)
        null
    }
}
