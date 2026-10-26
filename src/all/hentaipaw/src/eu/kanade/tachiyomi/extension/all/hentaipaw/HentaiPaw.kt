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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

@Source
class HentaiPaw(
    override val name: String,
    override val lang: String,
    baseUrl: String,
    override val id: Long,
) : KeiSource(),
    ConfigurableSource {

    override val baseUrl: String
        get() = apiUrl

    private val preferences by getPreferencesLazy()

    private val globalPrefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences("hentaipaw_global_prefs", 0)
    }

    private val apiUrl: String
        get() {
            val global = globalPrefs.getString(API_URL_PREF, "")?.trim().orEmpty().removeSuffix("/")
            if (global.isNotEmpty()) return global

            // Fallback: search source-specific preferences and migrate to global
            val fromSource = preferences.getString(API_URL_PREF, "")?.trim().orEmpty().removeSuffix("/")
            if (fromSource.isNotEmpty()) {
                Log.d(TAG, "[$lang] Migrating API URL from source pref to global: $fromSource")
                globalPrefs.edit().putString(API_URL_PREF, fromSource).apply()
                return fromSource
            }
            return ""
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
        val url = apiUrl
        if (url.isBlank()) {
            throw Exception("Adresse de l'API non configurée : veuillez renseigner l'URL de votre serveur Paw Bridge dans les paramètres de l'extension.")
        }
        val secret = apiSecret
        if (secret.isBlank()) {
            throw Exception("Clé API manquante : veuillez renseigner votre 'Clé secrète API' dans les paramètres de l'extension.")
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
            val currentApiHost = apiUrl.toHttpUrlOrNull()?.host

            if (currentApiHost != null && requestHost == currentApiHost) {
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

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val jsonSerializer by lazy {
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }

    private var inMemoryFilters: FilterOptionsDto? = null

    init {
        scope.launch {
            loadCachedFilters()
            fetchPopularFiltersAsync()
        }
    }

    private fun loadCachedFilters() {
        try {
            val raw = globalPrefs.getString(CACHED_FILTERS_PREF, null)
            if (!raw.isNullOrBlank()) {
                inMemoryFilters = jsonSerializer.decodeFromString<FilterOptionsDto>(raw)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error loading cached filters: ${e.message}")
        }
    }

    private fun fetchPopularFiltersAsync(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastFetch = globalPrefs.getLong(FILTERS_LAST_FETCH_PREF, 0L)
        if (!force && inMemoryFilters != null && inMemoryFilters!!.tags.isNotEmpty() && (now - lastFetch) < 24 * 60 * 60 * 1000L) {
            return
        }

        val url = apiUrl
        if (url.isBlank()) return
        val secret = apiSecret

        try {
            val req = okhttp3.Request.Builder()
                .url("$url/api/manga/filters/popular?limit=25")
                .apply {
                    if (secret.isNotBlank()) {
                        header("X-Paw-Secret", secret)
                        header("Authorization", "Bearer $secret")
                    }
                }
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body.string()
                    if (body.isNotBlank()) {
                        val parsed = jsonSerializer.decodeFromString<FilterOptionsDto>(body)
                        if (parsed.tags.isNotEmpty()) {
                            inMemoryFilters = parsed
                            globalPrefs.edit()
                                .putString(CACHED_FILTERS_PREF, body)
                                .putLong(FILTERS_LAST_FETCH_PREF, now)
                                .apply()
                            Log.d(TAG, "[$lang] Popular filters cache updated (tags=${parsed.tags.size})")
                        }
                    }
                } else {
                    Log.w(TAG, "[$lang] Popular filters fetch HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh popular filters in background: ${e.message}")
        }
    }

    private fun getFilterOptionsSafe(): FilterOptionsDto {
        if (inMemoryFilters == null || inMemoryFilters?.tags?.isEmpty() == true) {
            loadCachedFilters()
        }
        return inMemoryFilters ?: FilterOptionsDto()
    }

    // Popular Manga
    override suspend fun getPopularManga(page: Int): MangasPage {
        ensureConfigured()
        val targetLang = apiLang
        val url = if (targetLang != null) {
            "$baseUrl/api/manga/popular?page=$page&lang=$targetLang"
        } else {
            "$baseUrl/api/manga/popular?page=$page"
        }
        val dto = client.get(url).parseAs<MangaListPageDto>()
        return MangasPage(dto.mangas.map { it.toSManga() }, dto.hasNextPage)
    }

    // Latest Updates
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        ensureConfigured()
        val targetLang = apiLang
        val url = if (targetLang != null) {
            "$baseUrl/api/manga/latest?page=$page&lang=$targetLang"
        } else {
            "$baseUrl/api/manga/latest?page=$page"
        }
        val dto = client.get(url).parseAs<MangaListPageDto>()
        return MangasPage(dto.mangas.map { it.toSManga() }, dto.hasNextPage)
    }

    // Search Manga
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        ensureConfigured()
        val url = "$baseUrl/api/manga/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())

            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            }

            // Language filtering
            if (apiLang != null) {
                addQueryParameter("lang", apiLang!!)
            } else {
                val multiLang = filters.filterIsInstance<MultiLanguageFilter>().firstOrNull()
                val selectedLangs = multiLang?.state?.filter { it.state }?.map { it.code } ?: emptyList()
                if (selectedLangs.isNotEmpty()) {
                    addQueryParameter("langs", selectedLangs.joinToString(","))
                }
            }

            // Helper to extract checkbox IDs and text includes/excludes from a Group<Filter<*>>
            fun extractFacetGroup(group: Filter.Group<Filter<*>>?, includeParam: String, excludeParam: String) {
                if (group == null) return
                val checkedIds = group.state.filterIsInstance<FacetCheckBox>().filter { it.state }.map { it.id.toString() }
                val textInclude = group.state.filterIsInstance<TextIncludeFilter>().firstOrNull()?.state?.trim().orEmpty()
                val textExclude = group.state.filterIsInstance<TextExcludeFilter>().firstOrNull()?.state?.trim().orEmpty()

                val includes = mutableListOf<String>()
                includes.addAll(checkedIds)
                if (textInclude.isNotEmpty()) {
                    textInclude.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { includes.add(it) }
                }
                if (includes.isNotEmpty()) {
                    addQueryParameter(includeParam, includes.joinToString(","))
                }

                if (textExclude.isNotEmpty()) {
                    val excludes = textExclude.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (excludes.isNotEmpty()) {
                        addQueryParameter(excludeParam, excludes.joinToString(","))
                    }
                }
            }

            extractFacetGroup(filters.filterIsInstance<TagFilterGroup>().firstOrNull(), "tag", "exclude_tag")
            extractFacetGroup(filters.filterIsInstance<ArtistFilterGroup>().firstOrNull(), "artist", "exclude_artist")
            extractFacetGroup(filters.filterIsInstance<CharacterFilterGroup>().firstOrNull(), "character", "exclude_character")
            extractFacetGroup(filters.filterIsInstance<ParodyFilterGroup>().firstOrNull(), "parody", "exclude_parody")
            extractFacetGroup(filters.filterIsInstance<GroupFilterGroup>().firstOrNull(), "group", "exclude_group")

            // Pages count filters inside PagesFilterGroup
            val pagesGroup = filters.filterIsInstance<PagesFilterGroup>().firstOrNull()
            pagesGroup?.state?.filterIsInstance<MinPagesFilter>()?.firstOrNull()?.state?.trim()?.toIntOrNull()?.let {
                addQueryParameter("min_pages", it.toString())
            }
            pagesGroup?.state?.filterIsInstance<MaxPagesFilter>()?.firstOrNull()?.state?.trim()?.toIntOrNull()?.let {
                addQueryParameter("max_pages", it.toString())
            }

            // Sorting
            filters.filterIsInstance<SortFilter>().firstOrNull()?.selectedSort?.let {
                if (it.isNotEmpty()) addQueryParameter("sort", it)
            }
        }.build()

        val dto = client.get(url.toString()).parseAs<MangaListPageDto>()
        if (dto.mangas.isEmpty() && lang == "fr") {
            throw Exception("Aucune œuvre en français disponible sur HentaiPaw. Veuillez utiliser la source HentaiPaw (en) ou HentaiPaw (all).")
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

    // Filters UI
    override fun getFilterList(data: JsonElement?): FilterList {
        val opts = getFilterOptionsSafe()
        if (opts.tags.isEmpty()) {
            scope.launch { fetchPopularFiltersAsync(force = true) }
        }

        val filters = mutableListOf<Filter<*>>()

        // 1. Langues (source 'all' uniquement)
        if (lang == "all") {
            filters.add(MultiLanguageFilter())
        }

        // 2. Tri
        filters.add(SortFilter())

        // 3. Pages (catégorie pliable)
        filters.add(PagesFilterGroup())

        // 4. Catégories taxonomiques (titres épurés)
        filters.add(TagFilterGroup(opts.tags))
        filters.add(ArtistFilterGroup(opts.artists))
        filters.add(CharacterFilterGroup(opts.characters))
        filters.add(ParodyFilterGroup(opts.parodies))
        filters.add(GroupFilterGroup(opts.groups))

        return FilterList(filters)
    }

    // Filter Models
    class FacetCheckBox(name: String, val id: Int) : Filter.CheckBox(name)
    class TextIncludeFilter(name: String) : Filter.Text(name)
    class TextExcludeFilter(name: String) : Filter.Text(name)

    class TagFilterGroup(tags: List<FilterItemDto>) :
        Filter.Group<Filter<*>>(
            "Tags",
            tags.map { FacetCheckBox(it.name, it.id) } + listOf(
                TextIncludeFilter("Autre tag ou ID (+)"),
                TextExcludeFilter("Exclure tag (-)"),
            ),
        )

    class ArtistFilterGroup(artists: List<FilterItemDto>) :
        Filter.Group<Filter<*>>(
            "Artistes",
            artists.map { FacetCheckBox(it.name, it.id) } + listOf(
                TextIncludeFilter("Autre artiste ou ID (+)"),
                TextExcludeFilter("Exclure artiste (-)"),
            ),
        )

    class CharacterFilterGroup(characters: List<FilterItemDto>) :
        Filter.Group<Filter<*>>(
            "Personnages",
            characters.map { FacetCheckBox(it.name, it.id) } + listOf(
                TextIncludeFilter("Autre personnage ou ID (+)"),
                TextExcludeFilter("Exclure personnage (-)"),
            ),
        )

    class ParodyFilterGroup(parodies: List<FilterItemDto>) :
        Filter.Group<Filter<*>>(
            "Parodies",
            parodies.map { FacetCheckBox(it.name, it.id) } + listOf(
                TextIncludeFilter("Autre parodie ou ID (+)"),
                TextExcludeFilter("Exclure parodie (-)"),
            ),
        )

    class GroupFilterGroup(groups: List<FilterItemDto>) :
        Filter.Group<Filter<*>>(
            "Groupes",
            groups.map { FacetCheckBox(it.name, it.id) } + listOf(
                TextIncludeFilter("Autre groupe ou ID (+)"),
                TextExcludeFilter("Exclure groupe (-)"),
            ),
        )

    class MinPagesFilter : Filter.Text("Pages minimum")
    class MaxPagesFilter : Filter.Text("Pages maximum")

    class PagesFilterGroup :
        Filter.Group<Filter.Text>(
            "Pages",
            listOf(MinPagesFilter(), MaxPagesFilter()),
        )

    class LanguageCheckBox(name: String, val code: String) : Filter.CheckBox(name)

    class MultiLanguageFilter :
        Filter.Group<LanguageCheckBox>(
            "Langues",
            LANGUAGES.map { LanguageCheckBox(it.first, it.second) },
        ) {
        companion object {
            private val LANGUAGES = listOf(
                Pair("Français (FR)", "fr"),
                Pair("Anglais (US)", "us"),
                Pair("Japonais (JP)", "jp"),
                Pair("Chinois (CN)", "cn"),
                Pair("Espagnol (ES)", "es"),
                Pair("Coréen (KR)", "kr"),
                Pair("Portugais (PT)", "pt"),
            )
        }
    }

    class SortFilter :
        Filter.Select<String>(
            "Trier par",
            arrayOf("Derniers ajouts", "Popularité", "Nombre de pages"),
        ) {
        val selectedSort: String
            get() = when (state) {
                1 -> "popular"
                2 -> "pages"
                else -> ""
            }
    }

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = API_URL_PREF
            title = "Adresse de l'API Paw Bridge"
            summary = apiUrl.ifBlank { "Non configurée" }
            dialogTitle = "URL du serveur Paw Bridge"
            dialogMessage = "Entrez l'URL de votre serveur Paw Bridge (contacter le dev pour plus d'info)"
            text = apiUrl
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val newUrl = newValue.toString().trim().removeSuffix("/")
                Log.d(TAG, "[$lang] Setting new API URL: $newUrl")
                globalPrefs.edit().putString(API_URL_PREF, newUrl).apply()
                preferences.edit().putString(API_URL_PREF, newUrl).apply()
                summary = newUrl.ifBlank { "Non configurée" }
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = API_SECRET_PREF
            title = "Clé secrète API"
            summary = if (apiSecret.isNotBlank()) "••••••••" else "Non configurée"
            dialogTitle = "Clé secrète de l'API Paw Bridge"
            text = apiSecret
            setDefaultValue("")
            setOnPreferenceChangeListener { _, newValue ->
                val secret = newValue.toString().trim()
                Log.d(TAG, "[$lang] Setting new API secret (len=${secret.length})")
                globalPrefs.edit().putString(API_SECRET_PREF, secret).apply()
                preferences.edit().putString(API_SECRET_PREF, secret).apply()
                summary = if (secret.isNotBlank()) "••••••••" else "Non configurée"
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val TAG = "HentaiPaw"
        private const val API_URL_PREF = "paw_api_url"
        private const val API_SECRET_PREF = "paw_api_secret"
        private const val CACHED_FILTERS_PREF = "hentaipaw_cached_filters_json"
        private const val FILTERS_LAST_FETCH_PREF = "hentaipaw_filters_last_fetch"
    }
}
