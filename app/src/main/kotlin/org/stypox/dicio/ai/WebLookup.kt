package org.stypox.dicio.ai

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder

/**
 * Gives the AI brain access to the internet without any API key: Google News (RSS) for current
 * events and DuckDuckGo for everything else. Returns plain text the model can read.
 */
class WebLookup(private val httpClient: OkHttpClient) {

    suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        coroutineScope {
            val news = async { runCatching { googleNews(query) }.getOrElse { logFail("news", it) } }
            val web = async { runCatching { duckDuckGo(query) }.getOrElse { logFail("web", it) } }
            val parts = listOf(news.await(), web.await()).filter { it.isNotBlank() }
            if (parts.isEmpty()) "No search results could be retrieved." else parts.joinToString("\n\n")
        }
    }

    private fun googleNews(query: String): String {
        val url = "https://news.google.com/rss/search?q=" + enc(query) +
            "&hl=en-IN&gl=IN&ceid=IN:en"
        val xml = get(url)
        val items = Jsoup.parse(xml, "", Parser.xmlParser()).select("item").take(MAX_NEWS)
        if (items.isEmpty()) return ""
        return "News headlines:\n" + items.joinToString("\n") { item ->
            val title = item.selectFirst("title")?.text().orEmpty()
            val date = item.selectFirst("pubDate")?.text().orEmpty()
            "- $title ($date)"
        }
    }

    private fun duckDuckGo(query: String): String {
        val html = get(
            "https://html.duckduckgo.com/html/?q=" + enc(query),
            "text/html,application/xhtml+xml",
        )
        val results = Jsoup.parse(html).select("div.result__body").take(MAX_WEB).mapNotNull { r ->
            val title = r.selectFirst("a.result__a")?.text() ?: return@mapNotNull null
            val snippet = r.selectFirst(".result__snippet")?.text().orEmpty()
            val link = r.selectFirst("a.result__a")?.attr("href")
                ?.let { runCatching { Uri.parse(it).getQueryParameter("uddg") }.getOrNull() }
                ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                .orEmpty()
            "- $title [$link]: $snippet"
        }
        if (results.isEmpty()) return ""
        return "Web results:\n" + results.joinToString("\n")
    }

    private fun get(url: String, accept: String = "*/*"): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} for $url")
            return response.body.string()
        }
    }

    private fun logFail(what: String, t: Throwable): String {
        Log.w(TAG, "Web lookup ($what) failed", t)
        return ""
    }

    companion object {
        private val TAG = WebLookup::class.simpleName
        private const val MAX_NEWS = 8
        private const val MAX_WEB = 6
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/130.0 Mobile Safari/537.36"

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
