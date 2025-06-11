package eu.kanade.tachiyomi.animeextension.en.blzone

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidguardextractor.VidGuardExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BLZone : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "BLZone"
    override val baseUrl = "https://blzone.net"
    override val lang = "en"
    override val supportsLatest = true

    // ---- Preferences ----
    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred Server"
        private val PREF_SERVER_ENTRIES = arrayOf(
            "Filemoon", "Streamtape", "MixDrop", "VidGuard", "Upnshare", "P2P",
        )
        private val PREF_SERVER_VALUES = arrayOf(
            "filemoon", "streamtape", "mixdrop", "vidguard", "upnshare", "p2p",
        )
        private const val PREF_SERVER_DEFAULT = "filemoon"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ---- FILTERS ----
    private enum class Type(val path: String, val display: String) {
        ANIME("anime", "Anime"),
        DRAMA("dorama", "Drama"),
        BOTH("", "Both"),
    }

    private class TypeFilter : AnimeFilter.Select<String>(
        "Type",
        arrayOf(Type.BOTH.display, Type.ANIME.display, Type.DRAMA.display),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(TypeFilter())

    private fun getTypeFromFilters(filters: AnimeFilterList): Type {
        val typeIndex = (filters.getOrNull(0) as? AnimeFilter.Select<*>)?.state ?: 0
        return when (typeIndex) {
            1 -> Type.ANIME
            2 -> Type.DRAMA
            else -> Type.BOTH
        }
    }

    // ---- POPULAR ----
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        animeList.addAll(document.select("#dt-tvshows .item.tvshows").map { popularAnimeFromElement(it) })
        animeList.addAll(document.select("#dt-movies .item.tvshows").map { popularAnimeFromElement(it) })
        return AnimesPage(animeList, hasNextPage = false)
    }

    private fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val poster = element.selectFirst(".poster")
        val link = poster?.selectFirst("a")?.attr("href") ?: ""
        val img = poster?.selectFirst("img")
        anime.title = img?.attr("alt") ?: element.selectFirst("h3 a")?.text() ?: "No title"
        anime.thumbnail_url = img?.attr("src")
        anime.setUrlWithoutDomain(link)
        return anime
    }

    // ---- LATEST ----
    override fun latestUpdatesRequest(page: Int): Request {
        val animePageUrl = if (page == 1) "$baseUrl/anime/" else "$baseUrl/anime/page/$page/"
        return GET(animePageUrl, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        animeList.addAll(document.select(".items.full .item.tvshows").map { latestAnimeFromElement(it) })

        // Only fetch dorama for first page (to avoid duplicate pagination issues)
        if (response.request.url.encodedPath.endsWith("/anime/")) {
            try {
                val dramaResponse = client.newCall(GET("$baseUrl/dorama/", headers)).execute()
                val dramaDoc = dramaResponse.asJsoup()
                animeList.addAll(dramaDoc.select(".items.full .item.tvshows").map { latestAnimeFromElement(it) })
            } catch (_: Exception) {
                // Ignore errors on drama
            }
        }
        return AnimesPage(animeList, hasNextPage = hasNextPage(document))
    }

    private fun latestAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst(".pagination .next:not(.disabled)") != null
    }

    // ---- SEARCH ----
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val type = getTypeFromFilters(filters)
        val q = query.trim()
        return when (type) {
            Type.ANIME -> GET("$baseUrl/anime/?s=$q", headers)
            Type.DRAMA -> GET("$baseUrl/dorama/?s=$q", headers)
            Type.BOTH -> GET("$baseUrl/?s=$q", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select(".search-page .result-item article").map { searchAnimeFromElement(it) }
        return AnimesPage(animeList, hasNextPage = false)
    }

    private fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst(".thumbnail img")
        val link = element.selectFirst(".thumbnail a")?.attr("href") ?: ""
        anime.title = img?.attr("alt") ?: element.selectFirst(".title a")?.text() ?: "No title"
        anime.thumbnail_url = img?.attr("src")
        anime.setUrlWithoutDomain(link)
        return anime
    }

    // ---- DETAILS ----
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        val poster = document.selectFirst(".sheader .poster img")
        anime.title = document.selectFirst(".sheader .data h1")?.text() ?: poster?.attr("alt") ?: ""
        anime.thumbnail_url = poster?.attr("src")
        anime.genre = document.select(".sheader .sgeneros a").joinToString { it.text() }
        anime.description = document.selectFirst(".sbox .wp-content p")?.text() ?: ""
        val altTitle = document.selectFirst(".custom_fields b.variante:contains(Original Title) + span.valor")?.text()
        if (altTitle != null && altTitle.isNotBlank()) {
            anime.description += "\n\nOriginal Title: $altTitle"
        }
        return anime
    }

    // ---- EPISODES ----
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        // Reverse the list so newest episode is first!
        return document.select("#episodes ul.episodios2 > li").map { episodeFromElement(it) }.reversed()
    }

    private fun episodeFromElement(element: Element): SEpisode {
        val ep = SEpisode.create()
        val link = element.selectFirst(".episodiotitle a")?.attr("href") ?: ""
        ep.setUrlWithoutDomain(link)
        ep.name = element.selectFirst(".episodiotitle a")?.text() ?: "Episode"
        val episodeNum = Regex("""Episode (\d+)""", RegexOption.IGNORE_CASE).find(ep.name!!)?.groupValues?.getOrNull(1)
        ep.episode_number = episodeNum?.toFloatOrNull() ?: 1f
        ep.date_upload = 0L
        return ep
    }

    // ---- VIDEO EXTRACTORS ----
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val vidGuardExtractor by lazy { VidGuardExtractor(client) }

    // ---- VIDEO LIST PARSE ----
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val serverNames = document.select("#playeroptionsul li span.title").map { it.text().trim().lowercase() }
        val serverBoxes = document.select(".dooplay_player .source-box").drop(1)

        val videos = mutableListOf<Video>()
        serverBoxes.forEachIndexed { index, box ->
            val iframe = box.selectFirst("iframe.metaframe")
            val src = iframe?.attr("src")?.trim().orEmpty()
            if (src.isBlank()) return@forEachIndexed
            val name = serverNames.getOrNull(index) ?: "server${index + 1}"
            val videoUrl = if (src.contains("/diclaimer/?url=")) {
                java.net.URLDecoder.decode(src.substringAfter("/diclaimer/?url="), "UTF-8")
            } else {
                src
            }
            videos += Video(videoUrl, name, videoUrl)
        }
        return videos
    }

    // ---- GET VIDEO LIST ----
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET(baseUrl + episode.url)).await()
        val videos = videoListParse(response)

        val extractedVideos = mutableListOf<Video>()
        for (video in videos) {
            val url = video.url
            val videoName = video.quality.lowercase()
            when {
                url.contains("filemoon") -> extractedVideos += filemoonExtractor.videosFromUrl(url)
                url.contains("streamtape") -> extractedVideos += streamtapeExtractor.videosFromUrl(url)
                url.contains("mixdrop") -> extractedVideos += mixDropExtractor.videosFromUrl(url)
                url.contains("vgembed") || videoName.contains("vidguard") -> extractedVideos += vidGuardExtractor.videosFromUrl(url)
                // upnshare: only add if it is a direct mp4 link (as fallback, not as a primary server)
                (videoName.contains("upnshare") || url.contains("upns")) && url.endsWith(".mp4") -> extractedVideos += Video(url, "Upnshare", url)
                videoName.contains("p2p") || url.contains("p2p") -> extractedVideos += Video(url, "P2P", url)
                else -> extractedVideos += Video(url, video.quality.replaceFirstChar { it.uppercase() }, url)
            }
        }
        // Always enforce priority order: filemoon > vidguard > rest
        return extractedVideos.sortedWith(
            compareByDescending<Video> {
                when {
                    it.quality.lowercase().contains("filemoon") -> 2
                    it.quality.lowercase().contains("vidguard") -> 1
                    else -> 0
                }
            },
        )
    }
}
