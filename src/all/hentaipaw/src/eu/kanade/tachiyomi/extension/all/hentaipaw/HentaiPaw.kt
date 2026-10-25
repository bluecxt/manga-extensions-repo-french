package eu.kanade.tachiyomi.extension.all.hentaipaw

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.applicationContext
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
class HentaiPaw(
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    override val id: Long,
) : KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val globalPrefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("hentaipaw_global_prefs", 0)
    }

    private val apiSecret: String
        get() {
            val global = globalPrefs.getString(API_SECRET_PREF, "")?.trim().orEmpty()
            if (global.isNotEmpty()) return global

            // Fallback: search source-specific preferences and migrate to global
            val fromSource = preferences.getString(API_SECRET_PREF, "")?.trim().orEmpty()
            if (fromSource.isNotEmpty()) {
                Log.d(TAG, "[$lang] Migrating secret from source pref to global (len=${fromSource.length})")
                globalPrefs.edit().putString(API_SECRET_PREF, fromSource).apply()
                return fromSource
            }
            return ""
        }

    private val apiLang: String?
        get() = when (lang) {
            "en" -> "us"
            "fr" -> "fr"
            else -> null
        }

    private fun ensureConfigured() {
        val secret = apiSecret
        Log.d(TAG, "[$lang] ensureConfigured() checking secret: isBlank=${secret.isBlank()}")
        if (secret.isBlank()) {
            throw Exception("Clé API manquante : veuillez renseigner votre 'Clé secrète API' dans les paramètres de l'extension HentaiPaw.")
        }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder {
        add("Referer", "https://hentaipaw.com/")
        return this
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        addInterceptor { chain ->
            val request = chain.request()
            val requestHost = request.url.host
            val apiHost = baseUrl.toHttpUrl().host

            if (requestHost == apiHost) {
                val secret = apiSecret
                Log.d(TAG, "[$lang] API request: ${request.method} ${request.url.encodedPath} (hasSecret=${secret.isNotBlank()})")
                if (secret.isBlank()) {
                    throw Exception("Clé API manquante : veuillez renseigner votre 'Clé secrète API' dans les paramètres de l'extension HentaiPaw.")
                }
                val authenticatedRequest = request.newBuilder()
                    .header("X-Paw-Secret", secret)
                    .header("Authorization", "Bearer $secret")
                    .build()

                val response = chain.proceed(authenticatedRequest)
                Log.d(TAG, "[$lang] API response for ${request.url.encodedPath}: HTTP ${response.code}")
                if (response.code == 401) {
                    response.close()
                    throw Exception("Clé API invalide : vérifiez votre 'Clé secrète API' dans les paramètres de l'extension HentaiPaw.")
                }
                return@addInterceptor response
            }

            Log.d(TAG, "[$lang] Media request: ${request.url}")
            chain.proceed(request)
        }
        return this
    }

    private val filterOptions: FilterOptionsDto by lazy {
        try {
            val secret = apiSecret
            val req = okhttp3.Request.Builder()
                .url("$baseUrl/api/manga/filters")
                .header("X-Paw-Secret", secret)
                .header("Authorization", "Bearer $secret")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.parseAs<FilterOptionsDto>()
                } else {
                    FilterOptionsDto()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load filter options: ${e.message}")
            FilterOptionsDto()
        }
    }

    // Popular Manga
    override suspend fun getPopularManga(page: Int): MangasPage {
        ensureConfigured()
        val targetLang = apiLang
        val url = if (targetLang != null) {
            "$baseUrl/api/manga/search?page=$page&lang=$targetLang"
        } else {
            "$baseUrl/api/manga/popular?page=$page"
        }
        val dto = client.get(url).parseAs<MangaListPageDto>()
        if (dto.mangas.isEmpty() && lang == "fr") {
            throw Exception("Aucune œuvre en français disponible sur HentaiPaw (le catalogue propose : Anglais, Japonais, Chinois, Espagnol). Veuillez utiliser la source HentaiPaw (en) ou HentaiPaw (all).")
        }
        return MangasPage(dto.mangas.map { it.toSManga() }, dto.hasNextPage)
    }

    // Latest Updates
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        ensureConfigured()
        val targetLang = apiLang
        val url = if (targetLang != null) {
            "$baseUrl/api/manga/search?page=$page&lang=$targetLang"
        } else {
            "$baseUrl/api/manga/latest?page=$page"
        }
        val dto = client.get(url).parseAs<MangaListPageDto>()
        if (dto.mangas.isEmpty() && lang == "fr") {
            throw Exception("Aucune œuvre en français disponible sur HentaiPaw (le catalogue propose : Anglais, Japonais, Chinois, Espagnol). Veuillez utiliser la source HentaiPaw (en) ou HentaiPaw (all).")
        }
        return MangasPage(dto.mangas.map { it.toSManga() }, dto.hasNextPage)
    }

    // Search Manga
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        ensureConfigured()
        val url = "$baseUrl/api/manga/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())

            val targetLang = apiLang ?: filters.filterIsInstance<LanguageFilter>().firstOrNull()?.selected
            if (!targetLang.isNullOrEmpty()) {
                addQueryParameter("lang", targetLang)
            }

            filters.filterIsInstance<TagFilter>().firstOrNull()?.selectedId?.let {
                addQueryParameter("tagId", it.toString())
            }
            filters.filterIsInstance<ArtistFilter>().firstOrNull()?.selectedId?.let {
                addQueryParameter("artistId", it.toString())
            }
            filters.filterIsInstance<CharacterFilter>().firstOrNull()?.selectedId?.let {
                addQueryParameter("characterId", it.toString())
            }
            filters.filterIsInstance<ParodyFilter>().firstOrNull()?.selectedId?.let {
                addQueryParameter("parodyId", it.toString())
            }
            filters.filterIsInstance<GroupFilter>().firstOrNull()?.selectedId?.let {
                addQueryParameter("groupId", it.toString())
            }
        }.build()

        val dto = client.get(url.toString()).parseAs<MangaListPageDto>()
        if (dto.mangas.isEmpty() && lang == "fr") {
            throw Exception("Aucune œuvre en français disponible sur HentaiPaw (le catalogue propose : Anglais, Japonais, Chinois, Espagnol). Veuillez utiliser la source HentaiPaw (en) ou HentaiPaw (all).")
        }
        return MangasPage(dto.mangas.map { it.toSManga() }, dto.hasNextPage)
    }

    // Manga Details & Chapters Update
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        ensureConfigured()
        Log.d(TAG, "[$lang] fetchMangaUpdate for ${manga.url} (fetchDetails=$fetchDetails, fetchChapters=$fetchChapters)")
        val updatedManga = if (fetchDetails) {
            val dto = client.get("$baseUrl${manga.url}").parseAs<MangaDto>()
            dto.toSManga(isDetails = true)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val chapterDtos = client.get("$baseUrl${manga.url}/chapters").parseAs<List<ChapterDto>>()
            chapterDtos.map { dto ->
                SChapter.create().apply {
                    name = dto.name
                    chapter_number = dto.chapterNumber
                    date_upload = dto.dateUpload
                    url = "/api/manga/${dto.id}/pages"
                }
            }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override fun getMangaUrl(manga: SManga): String {
        val id = manga.url.substringAfterLast("/").substringBefore("?")
        return "https://hentaipaw.com/articles/$id"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val id = chapter.url.substringAfter("/api/manga/").substringBefore("/")
        return "https://hentaipaw.com/viewer?articleId=$id&page=1"
    }

    // Page List
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        ensureConfigured()
        val pages = client.get("$baseUrl${chapter.url}").parseAs<List<PageDto>>()
        return pages.map { Page(it.index, "", it.imageUrl) }
    }

    // Filters
    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>()
        val opts = filterOptions

        if (lang == "all") {
            filters.add(LanguageFilter())
        }
        if (opts.tags.isNotEmpty()) {
            filters.add(TagFilter(opts.tags))
        }
        if (opts.artists.isNotEmpty()) {
            filters.add(ArtistFilter(opts.artists))
        }
        if (opts.characters.isNotEmpty()) {
            filters.add(CharacterFilter(opts.characters))
        }
        if (opts.parodies.isNotEmpty()) {
            filters.add(ParodyFilter(opts.parodies))
        }
        if (opts.groups.isNotEmpty()) {
            filters.add(GroupFilter(opts.groups))
        }

        return FilterList(filters)
    }

    abstract class SelectFilter(name: String, private val items: List<FilterItemDto>) : Filter.Select<String>(name, (listOf("Tous") + items.map { it.name }).toTypedArray()) {
        val selectedId: Int?
            get() = if (state > 0 && state <= items.size) items[state - 1].id else null
    }

    class TagFilter(items: List<FilterItemDto>) : SelectFilter("Tag", items)
    class ArtistFilter(items: List<FilterItemDto>) : SelectFilter("Artiste", items)
    class CharacterFilter(items: List<FilterItemDto>) : SelectFilter("Personnage", items)
    class ParodyFilter(items: List<FilterItemDto>) : SelectFilter("Parodie", items)
    class GroupFilter(items: List<FilterItemDto>) : SelectFilter("Cercle / Groupe", items)

    private class LanguageFilter :
        Filter.Select<String>(
            "Langue",
            LANGUAGES.map { it.first }.toTypedArray(),
        ) {
        val selected: String
            get() = LANGUAGES[state].second

        companion object {
            private val LANGUAGES = listOf(
                Pair("Toutes", ""),
                Pair("Anglais", "us"),
                Pair("Français", "fr"),
                Pair("Japonais", "jp"),
                Pair("Chinois", "cn"),
                Pair("Espagnol", "es"),
                Pair("Coréen", "kr"),
                Pair("Portugais", "pt"),
            )
        }
    }

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = API_SECRET_PREF
            title = "Clé secrète API"
            summary = "Secret requis pour autoriser les requêtes vers l'API privée"
            text = apiSecret
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val secret = newValue.toString().trim()
                Log.d(TAG, "[$lang] Setting new API secret (len=${secret.length})")
                globalPrefs.edit().putString(API_SECRET_PREF, secret).apply()
                preferences.edit().putString(API_SECRET_PREF, secret).apply()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val TAG = "HentaiPaw"
        private const val API_SECRET_PREF = "paw_api_secret"
    }
}
