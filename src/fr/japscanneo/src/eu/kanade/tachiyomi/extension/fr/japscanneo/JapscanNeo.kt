package eu.kanade.tachiyomi.extension.fr.japscanneo

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.source.SMangaUpdate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

@Source
abstract class JapscanNeo :
    KeiSource(),
    ConfigurableSource {

    override val supportsLatest = true

    private val apiClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val baseUrl: String
        get() = preferences.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL)
            ?.trim()
            ?.trimEnd('/')
            ?.ifBlank { DEFAULT_SERVER_URL }
            ?: DEFAULT_SERVER_URL

    private val apiKey: String
        get() = preferences.getString(PREF_API_KEY, "")?.trim().orEmpty()

    override fun Headers.Builder.configureHeaders(): Headers.Builder {
        val token = apiKey
        if (token.isNotBlank()) {
            set("Authorization", "Bearer $token")
        }
        return this
    }

    private suspend fun apiGet(url: HttpUrl): Response {
        val configuredUrl = preferences.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
        if (configuredUrl.isBlank()) {
            throw Exception("API URL not configured: this extension is designed to work with a self-hosted proxy, contact the developer for more info.")
        }

        val response = try {
            apiClient.newCall(GET(url, headers)).awaitSuccess()
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("401") || msg.contains("403")) {
                if (apiKey.isBlank()) {
                    throw Exception("Missing API secret: please enter your 'API Secret Key' in the extension settings.")
                } else {
                    throw Exception("Invalid API secret: verify your 'API Secret Key' in the extension settings.")
                }
            }
            throw e
        }
        return response
    }

    private suspend fun apiGet(url: String): Response = apiGet(url.toHttpUrl())

    // Popular

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/api/manga".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "POPULAR")
            .build()
        val catalogPage = apiGet(url).parseAs<CatalogPageDto>()
        val mangas = catalogPage.items.map { item ->
            SManga.create().apply {
                this.url = "/api/manga/${item.slug}"
                title = item.title
                thumbnail_url = "$baseUrl/api/manga/${item.slug}/cover"
                status = parseStatus(item.status)
            }
        }
        return MangasPage(mangas, catalogPage.hasNextPage)
    }

    // Latest

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/api/manga/updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        val updates = apiGet(url).parseAs<List<UpdateItemDto>>()
        val mangas = updates.map { item ->
            SManga.create().apply {
                this.url = "/api/manga/${item.mangaSlug}"
                title = item.mangaTitle
                thumbnail_url = "$baseUrl/api/manga/${item.mangaSlug}/cover"
            }
        }
        return MangasPage(mangas, updates.isNotEmpty())
    }

    // Search

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/manga/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val results = apiGet(url).parseAs<List<MangaSummaryDto>>()
        val mangas = results.map { item ->
            SManga.create().apply {
                this.url = "/api/manga/${item.slug}"
                title = item.title
                thumbnail_url = "$baseUrl/api/manga/${item.slug}/cover"
                status = parseStatus(item.status)
            }
        }
        return MangasPage(mangas, false)
    }

    // Details & Chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = extractSlug(manga.url)
        val data = apiGet("$baseUrl/api/manga/$slug").parseAs<MangaWithChaptersDto>()
        val details = if (fetchDetails) {
            SManga.create().apply {
                url = "/api/manga/${data.details.slug}"
                title = data.details.title
                thumbnail_url = data.details.coverUrl
                description = data.details.synopsis
                author = data.details.author
                artist = data.details.artist ?: data.details.author
                status = parseStatus(data.details.status)
                genre = data.details.genres?.joinToString(", ")
            }
        } else {
            manga
        }
        val chapterList = if (fetchChapters) {
            data.chapters.map { ch ->
                SChapter.create().apply {
                    url = "/api/manga/${ch.mangaSlug}/chapters/${ch.chapterSlug}/pages"
                    name = ch.name
                    chapter_number = ch.chapterNumber
                    date_upload = ch.releaseDate ?: 0L
                }
            }
        } else {
            chapters
        }
        return SMangaUpdate(details, chapterList.asReversed())
    }

    // Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val path = chapter.url.removePrefix("/")
        val pageCount = apiGet("$baseUrl/$path").parseAs<PageCountResponseDto>()
        return (1..pageCount.totalPages).map { i ->
            Page(i - 1, imageUrl = "$baseUrl/$path/$i")
        }
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_SERVER_URL
            title = "URL du serveur"
            summary = "Adresse du serveur Japscan Neo (ex: http://192.168.1.10:8080)"
            setDefaultValue(DEFAULT_SERVER_URL)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_API_KEY
            title = "Clé API (optionnelle)"
            summary = "Jeton Bearer pour l'authentification auprès du serveur"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun extractSlug(url: String): String = url.trimEnd('/').substringAfterLast('/')

    private fun parseStatus(status: String?): Int = when (status?.uppercase()) {
        "ONGOING" -> SManga.ONGOING
        "COMPLETED" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    @Serializable
    private data class CatalogPageDto(
        val items: List<CatalogItemDto>,
        val hasNextPage: Boolean = false,
    )

    @Serializable
    private data class CatalogItemDto(
        val slug: String,
        val title: String,
        val coverUrl: String? = null,
        val status: String? = null,
    )

    @Serializable
    private data class UpdateItemDto(
        val mangaSlug: String,
        val mangaTitle: String,
        val coverUrl: String? = null,
    )

    @Serializable
    private data class MangaSummaryDto(
        val slug: String,
        val title: String,
        val coverUrl: String? = null,
        val status: String? = null,
    )

    @Serializable
    private data class MangaWithChaptersDto(
        val details: MangaDetailsDto,
        val chapters: List<ChapterInfoDto>,
    )

    @Serializable
    private data class MangaDetailsDto(
        val slug: String,
        val title: String,
        val coverUrl: String? = null,
        val synopsis: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val status: String? = null,
        val genres: List<String>? = null,
    )

    @Serializable
    private data class ChapterInfoDto(
        val chapterSlug: String,
        val mangaSlug: String,
        val chapterNumber: Float,
        val name: String,
        val releaseDate: Long? = null,
    )

    @Serializable
    private data class PageCountResponseDto(
        val chapterId: String,
        val totalPages: Int,
    )

    companion object {
        private const val PREF_SERVER_URL = "japscan_neo_server_url"
        private const val PREF_API_KEY = "japscan_neo_api_key"
        private const val DEFAULT_SERVER_URL = "http://localhost:8080"
    }
}
