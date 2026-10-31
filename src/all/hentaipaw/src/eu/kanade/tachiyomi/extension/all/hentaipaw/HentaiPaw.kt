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

    init {
        loadCachedFilters()
        scope.launch {
            fetchPopularFiltersAsync()
        }
    }

    private fun loadCachedFilters() {
        if (inMemoryFilters != null && inMemoryFilters!!.tags.isNotEmpty()) return
        try {
            val raw = globalPrefs.getString(CACHED_FILTERS_PREF, null)
            if (!raw.isNullOrBlank()) {
                val parsed = jsonSerializer.decodeFromString<FilterOptionsDto>(raw)
                if (parsed.tags.isNotEmpty()) {
                    inMemoryFilters = parsed
                }
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
        val current = inMemoryFilters
        if (current != null && current.tags.isNotEmpty()) return current
        loadCachedFilters()
        val loaded = inMemoryFilters
        return if (loaded != null && loaded.tags.isNotEmpty()) loaded else DEFAULT_FILTER_OPTIONS
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
        val allFilters = buildList {
            filters.forEach { filter ->
                add(filter)
                if (filter is Filter.Group<*>) {
                    filter.state.forEach { child ->
                        if (child is Filter<*>) add(child)
                    }
                }
            }
        }

        val url = "$baseUrl/api/manga/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())

            if (query.isNotBlank()) {
                addQueryParameter("q", query.trim())
            }

            // Language filtering
            if (apiLang != null) {
                addQueryParameter("lang", apiLang!!)
            } else {
                val multiLang = allFilters.filterIsInstance<MultiLanguageFilter>().firstOrNull()
                val selectedLangs = multiLang?.state?.filter { it.state }?.map { it.code } ?: emptyList()
                if (selectedLangs.isNotEmpty()) {
                    addQueryParameter("langs", selectedLangs.joinToString(","))
                }
            }

            fun <C : FacetCheckBox, I : Filter.Text, E : Filter.Text> extractFacet(
                checkboxClass: Class<C>,
                includeClass: Class<I>,
                excludeClass: Class<E>,
                incParam: String,
                excParam: String,
            ) {
                val checkedIds = allFilters.filter { checkboxClass.isInstance(it) }
                    .map { it as FacetCheckBox }
                    .filter { it.state }
                    .map { it.id.toString() }

                val textInc = allFilters.filter { includeClass.isInstance(it) }
                    .map { (it as Filter.Text).state.trim() }
                    .firstOrNull().orEmpty()

                val textExc = allFilters.filter { excludeClass.isInstance(it) }
                    .map { (it as Filter.Text).state.trim() }
                    .firstOrNull().orEmpty()

                val includes = mutableListOf<String>()
                includes.addAll(checkedIds)
                if (textInc.isNotEmpty()) {
                    textInc.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { includes.add(it) }
                }
                if (includes.isNotEmpty()) {
                    addQueryParameter(incParam, includes.joinToString(","))
                }

                if (textExc.isNotEmpty()) {
                    val excludes = textExc.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (excludes.isNotEmpty()) {
                        addQueryParameter(excParam, excludes.joinToString(","))
                    }
                }
            }

            extractFacet(TagCheckBox::class.java, TagIncludeFilter::class.java, TagExcludeFilter::class.java, "tag", "exclude_tag")
            extractFacet(ArtistCheckBox::class.java, ArtistIncludeFilter::class.java, ArtistExcludeFilter::class.java, "artist", "exclude_artist")
            extractFacet(CharacterCheckBox::class.java, CharacterIncludeFilter::class.java, CharacterExcludeFilter::class.java, "character", "exclude_character")
            extractFacet(ParodyCheckBox::class.java, ParodyIncludeFilter::class.java, ParodyExcludeFilter::class.java, "parody", "exclude_parody")
            extractFacet(GroupCheckBox::class.java, GroupIncludeFilter::class.java, GroupExcludeFilter::class.java, "group", "exclude_group")

            allFilters.filterIsInstance<MinPagesFilter>().firstOrNull()?.state?.trim()?.toIntOrNull()?.let {
                addQueryParameter("min_pages", it.toString())
            }
            allFilters.filterIsInstance<MaxPagesFilter>().firstOrNull()?.state?.trim()?.toIntOrNull()?.let {
                addQueryParameter("max_pages", it.toString())
            }

            // Sorting & Seed
            val sortFilter = allFilters.filterIsInstance<SortFilter>().firstOrNull()
            val sort = sortFilter?.selectedSort.orEmpty()
            if (sort.isNotEmpty()) {
                addQueryParameter("sort", sort)
                if (sort == "random") {
                    if (page == 1) {
                        currentSearchSeed = (System.currentTimeMillis() xor kotlin.random.Random.nextLong()) and 0x7FFFFFFF
                    }
                    addQueryParameter("seed", currentSearchSeed.toString())
                }
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
        if (inMemoryFilters == null || inMemoryFilters?.tags?.isEmpty() == true) {
            scope.launch { fetchPopularFiltersAsync(force = true) }
        }

        val filters = mutableListOf<Filter<*>>()

        // 1. Langues (source 'all' uniquement)
        if (lang == "all") {
            filters.add(MultiLanguageFilter())
        }

        // 2. Tri
        filters.add(SortFilter())

        // 3. Pages
        filters.add(PageFoldable())

        // 4. Tags
        filters.add(TagFoldable(opts.tags))

        // 5. Artistes
        filters.add(ArtistFoldable(opts.artists))

        // 6. Personnages
        filters.add(CharacterFoldable(opts.characters))

        // 7. Parodies
        filters.add(ParodyFoldable(opts.parodies))

        // 8. Groupes
        filters.add(GroupFoldable(opts.groups))

        return FilterList(filters)
    }

    // Filter Models
    open class FoldableFilter(name: String, filters: List<Filter<*>>) : Filter.Group<Filter<*>>(name, filters)
    open class FacetCheckBox(name: String, val id: Int) : Filter.CheckBox(name)

    // Tags
    class TagCheckBox(name: String, id: Int) : FacetCheckBox(name, id)
    class TagIncludeFilter : Filter.Text("Inclure tag (+)")
    class TagExcludeFilter : Filter.Text("Exclure tag (-)")
    class TagFoldable(tags: List<FilterItemDto>) :
        FoldableFilter(
            "Tags",
            tags.map { TagCheckBox(it.name, it.id) } + listOf(TagIncludeFilter(), TagExcludeFilter()),
        )

    // Artistes
    class ArtistCheckBox(name: String, id: Int) : FacetCheckBox(name, id)
    class ArtistIncludeFilter : Filter.Text("Inclure artiste (+)")
    class ArtistExcludeFilter : Filter.Text("Exclure artiste (-)")
    class ArtistFoldable(artists: List<FilterItemDto>) :
        FoldableFilter(
            "Artistes",
            artists.map { ArtistCheckBox(it.name, it.id) } + listOf(ArtistIncludeFilter(), ArtistExcludeFilter()),
        )

    // Personnages
    class CharacterCheckBox(name: String, id: Int) : FacetCheckBox(name, id)
    class CharacterIncludeFilter : Filter.Text("Inclure personnage (+)")
    class CharacterExcludeFilter : Filter.Text("Exclure personnage (-)")
    class CharacterFoldable(characters: List<FilterItemDto>) :
        FoldableFilter(
            "Personnages",
            characters.map { CharacterCheckBox(it.name, it.id) } + listOf(CharacterIncludeFilter(), CharacterExcludeFilter()),
        )

    // Parodies
    class ParodyCheckBox(name: String, id: Int) : FacetCheckBox(name, id)
    class ParodyIncludeFilter : Filter.Text("Inclure parodie (+)")
    class ParodyExcludeFilter : Filter.Text("Exclure parodie (-)")
    class ParodyFoldable(parodies: List<FilterItemDto>) :
        FoldableFilter(
            "Parodies",
            parodies.map { ParodyCheckBox(it.name, it.id) } + listOf(ParodyIncludeFilter(), ParodyExcludeFilter()),
        )

    // Groupes
    class GroupCheckBox(name: String, id: Int) : FacetCheckBox(name, id)
    class GroupIncludeFilter : Filter.Text("Inclure groupe (+)")
    class GroupExcludeFilter : Filter.Text("Exclure groupe (-)")
    class GroupFoldable(groups: List<FilterItemDto>) :
        FoldableFilter(
            "Groupes",
            groups.map { GroupCheckBox(it.name, it.id) } + listOf(GroupIncludeFilter(), GroupExcludeFilter()),
        )

    // Pages
    class MinPagesFilter : Filter.Text("Minimum")
    class MaxPagesFilter : Filter.Text("Maximum")
    class PageFoldable :
        FoldableFilter(
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
            arrayOf("Derniers ajouts", "Popularité", "Nombre de pages", "Aléatoire"),
        ) {
        val selectedSort: String
            get() = when (state) {
                1 -> "popular"
                2 -> "pages"
                3 -> "random"
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

        @Volatile
        private var inMemoryFilters: FilterOptionsDto? = null

        @Volatile
        private var currentSearchSeed: Long = 12345L

        private val DEFAULT_FILTER_OPTIONS = FilterOptionsDto(
            tags = listOf(
                FilterItemDto(5744, "sole female"),
                FilterItemDto(186, "big breasts"),
                FilterItemDto(5745, "sole male"),
                FilterItemDto(4170, "nakadashi"),
                FilterItemDto(7497, "rough translation"),
                FilterItemDto(447, "blowjob"),
                FilterItemDto(5275, "schoolgirl uniform"),
                FilterItemDto(1400, "full color"),
                FilterItemDto(2577, "mosaic censorship"),
                FilterItemDto(752, "defloration"),
                FilterItemDto(12053, "dilf"),
                FilterItemDto(1581, "glasses"),
                FilterItemDto(5440, "shotacon"),
                FilterItemDto(10328, "aunt"),
                FilterItemDto(2496, "milf"),
                FilterItemDto(7268, "x-ray"),
                FilterItemDto(639, "collar"),
                FilterItemDto(80, "anal"),
                FilterItemDto(6296, "sweating"),
                FilterItemDto(730, "dark skin"),
                FilterItemDto(6005, "stockings"),
                FilterItemDto(2412, "masturbation"),
                FilterItemDto(1094, "femdom"),
                FilterItemDto(1836, "impregnation"),
                FilterItemDto(1033, "exhibitionism"),
            ),
            artists = listOf(
                FilterItemDto(4540, "dikk0"),
                FilterItemDto(45077, "kanamirubon"),
                FilterItemDto(40938, "shiroi yoru"),
                FilterItemDto(42398, "osuwaani"),
                FilterItemDto(40535, "eggutarto"),
                FilterItemDto(41618, "a-da-ko"),
                FilterItemDto(42749, "tanashi takashi"),
                FilterItemDto(44770, "matsubara toori"),
                FilterItemDto(44962, "yamashiro oki"),
                FilterItemDto(44510, "usukuchi"),
                FilterItemDto(45367, "m hakase"),
                FilterItemDto(30668, "yu-ji"),
                FilterItemDto(44096, "aoi oni"),
                FilterItemDto(40564, "inukai mofu"),
                FilterItemDto(45396, "gamarenji"),
                FilterItemDto(30496, "yoshikawa hideaki"),
                FilterItemDto(15992, "mimonel"),
                FilterItemDto(10369, "kageusu gokuto"),
                FilterItemDto(14645, "mahouya"),
                FilterItemDto(4882, "ebifurya"),
                FilterItemDto(26520, "takeda hiromitsu"),
                FilterItemDto(27041, "tatsuya"),
                FilterItemDto(14863, "mana"),
                FilterItemDto(39904, "sonao"),
                FilterItemDto(44372, "tefuru"),
            ),
            characters = listOf(
                FilterItemDto(19219, "sensei"),
                FilterItemDto(4699, "gudao"),
                FilterItemDto(25811, "kei tendou"),
                FilterItemDto(14871, "shirakami fubuki"),
                FilterItemDto(12479, "producer"),
                FilterItemDto(11195, "nami"),
                FilterItemDto(24008, "kisaki ryuuge"),
                FilterItemDto(21417, "wise"),
                FilterItemDto(19410, "rio tsukatsuki"),
                FilterItemDto(16144, "teitoku"),
                FilterItemDto(18061, "yuuka hayase"),
                FilterItemDto(25790, "cure arcana shadow"),
                FilterItemDto(18654, "mika misono"),
                FilterItemDto(11363, "naruto uzumaki"),
                FilterItemDto(1266, "asuna ichinose"),
                FilterItemDto(19183, "fern"),
                FilterItemDto(5648, "hoshimachi suisei"),
                FilterItemDto(19116, "noa ushio"),
                FilterItemDto(5395, "hina sorasaki"),
                FilterItemDto(24100, "nozomi tachibana"),
                FilterItemDto(567, "alice tendou"),
                FilterItemDto(14655, "shielder"),
                FilterItemDto(12896, "reimu hakurei"),
                FilterItemDto(11587, "nico robin"),
                FilterItemDto(4056, "france"),
            ),
            parodies = listOf(
                FilterItemDto(2807, "original"),
                FilterItemDto(443, "blue archive"),
                FilterItemDto(1584, "hololive"),
                FilterItemDto(1069, "fate grand order"),
                FilterItemDto(3802, "the idolmaster"),
                FilterItemDto(2954, "pokemon"),
                FilterItemDto(3955, "touhou project"),
                FilterItemDto(4552, "zenless zone zero"),
                FilterItemDto(1265, "genshin impact"),
                FilterItemDto(2668, "nijisanji"),
                FilterItemDto(1886, "kantai collection"),
                FilterItemDto(231, "arknights"),
                FilterItemDto(5260, "honkai star rail"),
                FilterItemDto(303, "azur lane"),
                FilterItemDto(2763, "one piece"),
                FilterItemDto(4049, "uma musume pretty derby"),
                FilterItemDto(2620, "naruto"),
                FilterItemDto(2586, "my hero academia"),
                FilterItemDto(4593, "goddess of victory nikke"),
                FilterItemDto(5749, "meitantei precure"),
                FilterItemDto(432, "bleach"),
                FilterItemDto(4588, "sousou no frieren"),
                FilterItemDto(2990, "princess connect"),
                FilterItemDto(295, "axis powers hetalia"),
                FilterItemDto(1784, "jujutsu kaisen"),
            ),
            groups = listOf(
                FilterItemDto(29882, "seisui bar"),
                FilterItemDto(16050, "yuujikouji"),
                FilterItemDto(11691, "rere"),
                FilterItemDto(22330, "misaki"),
                FilterItemDto(32479, "muru no honbako"),
                FilterItemDto(865, "fatalpulse"),
                FilterItemDto(6666, "squeeze candy heaven"),
                FilterItemDto(324, "naruho-dou"),
                FilterItemDto(25991, "owakari itadaketa darou ka"),
                FilterItemDto(26121, "hyoui lover"),
                FilterItemDto(12472, "amuai okashi seisakusho"),
                FilterItemDto(31116, "philia"),
                FilterItemDto(125, "karakishi youhei-dan"),
                FilterItemDto(8974, "nyuu koubou"),
                FilterItemDto(23618, "mofumofuen"),
                FilterItemDto(26339, "sakura no tomoru hie"),
                FilterItemDto(5868, "marked-two"),
                FilterItemDto(4586, "noraneko-no-tama"),
                FilterItemDto(21501, "hokyuun"),
                FilterItemDto(32168, "yama danchi"),
                FilterItemDto(15584, "pecan"),
                FilterItemDto(268, "nekomataya"),
                FilterItemDto(31879, "line out"),
                FilterItemDto(1074, "acid-head"),
                FilterItemDto(26656, "seika kairaku shoten"),
            ),
        )
    }
}
