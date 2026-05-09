package eu.kanade.tachiyomi.extension.fr.crunchyscan

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class CrunchyScan : ParsedHttpSource() {

    override val name = "CrunchyScan"
    override val baseUrl = "https://cdn.crunchyscan.fr"
    override val lang = "fr"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val readerJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private var csrfToken: String? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private fun getHeaders(token: String): Headers = headers.newBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("X-CSRF-TOKEN", token)
        .add("Accept", "application/json, text/plain, */*")
        .build()

    private fun fetchToken(): String {
        if (csrfToken == null) {
            Log.d("CrunchyScan", "Fetching CSRF token")
            try {
                val response = client.newCall(GET("$baseUrl/catalog", headers)).execute()
                val doc = response.asJsoup()
                csrfToken = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
                if (csrfToken == null) {
                    val res2 = client.newCall(GET("$baseUrl/", headers)).execute()
                    csrfToken = res2.asJsoup().selectFirst("meta[name=csrf-token]")?.attr("content")
                }
                Log.d("CrunchyScan", "Fetched token: $csrfToken")
            } catch (e: Exception) {
                Log.e("CrunchyScan", "Error fetching token", e)
            }
        }
        return csrfToken ?: ""
    }

    // ============================== Popular ===============================
    override fun popularMangaRequest(page: Int): Request {
        val token = fetchToken()
        val payload = buildJsonObject {
            put("affichage", "grid")
            put("searchTerm", "")
            put("page", page)
            put("orderWith", "Vues")
            put("orderBy", "desc")
            putJsonArray("chapters") {
                add(0)
                add(2000)
            }
            put("team", "")
            put("artist", "")
            put("author", "")
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/api/manga/search/advance", getHeaders(token), body)
    }

    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)

    override fun popularMangaSelector() = throw UnsupportedOperationException()

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val anchor = element.selectFirst("a.font-bold, a.chapter-link")
        setUrlWithoutDomain(anchor?.attr("href") ?: "")
        title = anchor?.text()?.trim() ?: ""
        thumbnail_url = element.selectFirst("img")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
    }

    override fun popularMangaNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val token = fetchToken()
        val payload = buildJsonObject {
            put("affichage", "grid")
            put("searchTerm", "")
            put("page", page)
            put("orderWith", "Récent")
            put("orderBy", "desc")
            putJsonArray("chapters") {
                add(0)
                add(2000)
            }
            put("team", "")
            put("artist", "")
            put("author", "")
        }
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/api/manga/search/advance", getHeaders(token), body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = searchMangaParse(response)

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty() && filters.isEmpty()) {
            return GET("$baseUrl/api/manga/search/manga/$query", headers)
        }

        val token = fetchToken()
        val payload = buildJsonObject {
            put("affichage", "grid")
            put("searchTerm", query)
            put("page", page)
            put("team", "")
            put("artist", "")
            put("author", "")
            putJsonArray("chapters") {
                add(0)
                add(2000)
            }

            var orderWith = "Récent"
            var orderBy = "desc"

            filters.forEach { filter ->
                when (filter) {
                    is TypeFilter -> {
                        val active = filter.state.filter { it.state }
                        if (active.isNotEmpty()) {
                            putJsonArray("types") { active.forEach { add(it.name) } }
                        }
                    }
                    is StatusFilter -> {
                        val active = filter.state.filter { it.state }
                        if (active.isNotEmpty()) {
                            putJsonArray("status") { active.forEach { add(it.name) } }
                        }
                    }
                    is GenreFilter -> {
                        val active = filter.state.filter { it.state }
                        if (active.isNotEmpty()) {
                            putJsonArray("genres") { active.forEach { add(it.name) } }
                        }
                    }
                    is OrderByFilter -> {
                        orderWith = filter.getOrderWith()
                        orderBy = filter.getOrderBy()
                    }
                    is YearFilter -> {
                        val active = filter.state.filter { it.state }
                        if (active.isNotEmpty()) {
                            putJsonArray("year") { active.forEach { add(it.name) } }
                        }
                    }
                    else -> {}
                }
            }

            put("orderWith", orderWith)
            put("orderBy", orderBy)
        }

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/api/manga/search/advance", getHeaders(token), body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val bodyString = response.body.string()
        if (bodyString.isBlank()) return MangasPage(emptyList(), false)

        return try {
            if (bodyString.trim().startsWith("[")) {
                val result = json.decodeFromString<List<SearchMangaDto>>(bodyString)
                val mangas = result.map { it.toSManga() }
                MangasPage(mangas, false)
            } else {
                val result = json.decodeFromString<SearchResponseDto>(bodyString)
                val mangas = result.data.map { it.toSManga() }
                val currentPage = result.meta?.currentPage ?: result.currentPage ?: 1
                val lastPage = result.meta?.lastPage ?: result.lastPage ?: 1
                MangasPage(mangas, currentPage < lastPage)
            }
        } catch (e: Exception) {
            Log.e("CrunchyScan", "JSON Parsing Error: ${e.message}")
            if (bodyString.length < 2000) Log.d("CrunchyScan", "Body content: $bodyString")
            throw e
        }
    }

    private fun SearchMangaDto.toSManga() = SManga.create().apply {
        url = "/lecture-en-ligne/$slug"
        title = name
        thumbnail_url = coverUrl?.let {
            if (it.startsWith("http")) it else "$baseUrl$it"
        }
    }

    override fun searchMangaSelector() = throw UnsupportedOperationException()
    override fun searchMangaFromElement(element: Element) = throw UnsupportedOperationException()
    override fun searchMangaNextPageSelector(): String? = null

    // =========================== Manga Details ============================
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val jsonLd = document.selectFirst("script[type=application/ld+json]")?.data()
        if (jsonLd != null) {
            try {
                val content = json.parseToJsonElement(jsonLd).jsonObject
                title = content["name"]?.jsonPrimitive?.content ?: ""
                description = content["description"]?.jsonPrimitive?.content ?: ""
                thumbnail_url = content["image"]?.jsonPrimitive?.content ?: ""
                genre = content["genre"]?.jsonPrimitive?.content ?: ""

                val authorList = content["author"]?.jsonArray
                author = authorList?.joinToString { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }

                val artistList = content["illustrator"]?.jsonArray ?: content["editor"]?.jsonArray
                artist = artistList?.joinToString { it.jsonObject["name"]?.jsonPrimitive?.content ?: "" }

                return@apply
            } catch (e: Exception) {
                Log.e("CrunchyScan", "Error parsing JSON-LD", e)
            }
        }

        // Fallback to Jsoup
        title = document.selectFirst("h1")?.text() ?: ""
        description = document.select("div.mt-12.max-h-48 p, div.flex.flex-col.gap-2.mt-5 > p, div#description, p#synopsis").joinToString("\n") { it.text() }.trim()
        thumbnail_url = document.selectFirst("img.manga_cover, img.manga-cover, img.rounded.object-cover")?.let {
            it.absUrl("data-src").ifEmpty { it.absUrl("src") }
        }
        genre = document.select("h3[aria-label*='genre' i] + div a, div.flex.flex-wrap.gap-2 > a[href*='genre']").joinToString { it.text() }
        status = when (document.selectFirst("h3[aria-label*='Status' i] + p, span.px-2.py-1.rounded-md.text-xs:contains(Statut)")?.text()?.trim()) {
            "En cours" -> SManga.ONGOING
            "Terminé" -> SManga.COMPLETED
            "En pause" -> SManga.ON_HIATUS
            "Abandonné" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        author = document.select("h3[aria-label*='auteur' i] + div a, p:contains(Auteur) + p, span:contains(Auteur) + span").joinToString { it.text() }.ifEmpty { null }
        artist = document.select("h3[aria-label*='artiste' i] + div a, p:contains(Artiste) + p, span:contains(Artiste) + span").joinToString { it.text() }.ifEmpty { null }
    }

    override fun relatedMangaListParse(response: Response): List<SManga> = emptyList()
    override fun relatedMangaListSelector(): String = throw UnsupportedOperationException()
    override fun relatedMangaFromElement(element: Element): SManga = throw UnsupportedOperationException()

    // ============================== Chapters ==============================
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val jsonLd = document.selectFirst("script[type=application/ld+json]")?.data()
        if (jsonLd != null) {
            try {
                val content = json.parseToJsonElement(jsonLd).jsonObject
                val parts = content["hasPart"]?.jsonArray
                if (parts != null) {
                    return parts.map {
                        val part = it.jsonObject
                        SChapter.create().apply {
                            url = part["url"]?.jsonPrimitive?.content?.let { u ->
                                if (u.startsWith("http")) {
                                    "/" + u.substringAfter(baseUrl).removePrefix("/")
                                } else {
                                    u
                                }
                            } ?: ""
                            name = part["name"]?.jsonPrimitive?.content ?: ""
                            date_upload = part["datePublished"]?.jsonPrimitive?.content?.let { d ->
                                try {
                                    dateFormat.parse(d)?.time ?: 0L
                                } catch (e: Exception) {
                                    0L
                                }
                            } ?: 0L
                        }
                    }.reversed()
                }
            } catch (e: Exception) {
                Log.e("CrunchyScan", "Error parsing chapters JSON-LD", e)
            }
        }
        return super.chapterListParse(response)
    }

    override fun chapterListSelector() = "div#ChapterWrap a.chapter-link, div#ChapterWrap a.flex.bg-secondary"

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text().trim().replace("\n", " ").replace(Regex("\\s+"), " ")
    }

    // =============================== Pages ================================
    override fun pageListParse(document: Document): List<Page> {
        val encryptedData = document.selectFirst("div#readerList, div.reader-images")?.attr("data-meta")
        if (encryptedData != null) {
            return try {
                decryptPages(encryptedData, document)
            } catch (e: Exception) {
                Log.e("CrunchyScan", "Decryption failed", e)
                emptyList()
            }
        }

        val pages = document.select("div#readerList img, div.reader-images img, img.reader-image, img.manga-page").mapIndexed { i, img ->
            val url = img.absUrl("data-src").ifEmpty {
                img.absUrl("data-lazy-src").ifEmpty {
                    img.absUrl("src")
                }
            }
            Page(i, "", url)
        }
        Log.d("CrunchyScan", "Fetched ${pages.size} pages")
        return pages
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    private fun decryptPages(hexData: String, document: Document): List<Page> {
        // TODO: Implement WASM-derived AES decryption
        // For now, let's try to find if there's a fallback or if we can replicate the logic
        return emptyList()
    }

    @Serializable
    data class SearchResponseDto(
        val data: List<SearchMangaDto> = emptyList(),
        val meta: SearchMetaDto? = null,
        @SerialName("current_page") val currentPage: Int? = null,
        @SerialName("last_page") val lastPage: Int? = null,
    )

    @Serializable
    data class SearchMetaDto(
        @SerialName("current_page") val currentPage: Int,
        @SerialName("last_page") val lastPage: Int,
    )

    @Serializable
    data class SearchMangaDto(
        val slug: String,
        val name: String,
        @SerialName("cover_url") val coverUrl: String? = null,
        val synopsis: String? = null,
    )

    // ============================== Filters ===============================

    override fun getFilterList() = FilterList(
        OrderByFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
        YearFilter(),
    )

    class OrderByFilter :
        Filter.Select<String>(
            "Trier par",
            arrayOf("Récent", "Vues", "Note", "Alphabétiquement"),
        ) {
        fun getOrderWith() = when (state) {
            1 -> "Vues"
            2 -> "Note"
            3 -> "Nom"
            else -> "Récent"
        }

        fun getOrderBy() = when (state) {
            3 -> "asc"
            else -> "desc"
        }
    }

    class TypeFilter :
        Filter.Group<CheckBoxFilter>(
            "Types",
            listOf("Manga", "Manhwa", "Manhua", "Bande Dessinée").map { CheckBoxFilter(it) },
        )

    class StatusFilter :
        Filter.Group<CheckBoxFilter>(
            "Statut",
            listOf("En cours", "Terminé", "En pause", "Abandonné").map { CheckBoxFilter(it) },
        )

    class GenreFilter :
        Filter.Group<CheckBoxFilter>(
            "Genres",
            listOf(
                "Action", "Amour", "Aventure", "Arts Martiaux", "Combats", "Comédie", "Démons", "Drame",
                "Fantastique", "Harem", "Historique", "Guerre", "Horreur", "Isekai", "Magie", "Mechas",
                "Militaire", "Monstres", "Mystère", "Mature", "Post Apocalyptique", "Psychologique",
                "Réincarnation", "Romance", "Science Fiction", "Sport", "Surnaturel", "Thriller",
                "Tranche De Vie", "Vie Scolaire", "Piccoma", "Delitoon", "Toomics", "Webtoon",
                "Tappytoon", "Ono", "BL", "Oneshot", "Yuri", "Adulte", "Pocket Comics", "Pornhwa",
                "Mangadon", "HoneyToon",
            ).map { CheckBoxFilter(it) },
        )

    class YearFilter :
        Filter.Group<CheckBoxFilter>(
            "Années",
            (2025 downTo 1987).map { CheckBoxFilter(it.toString()) },
        )

    class CheckBoxFilter(name: String) : Filter.CheckBox(name)
}
