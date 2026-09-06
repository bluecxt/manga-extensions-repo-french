package eu.kanade.tachiyomi.extension.all.ninemanga

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH)

@Source
abstract class NineManga : HttpSource() {

    override val supportsLatest: Boolean = true

    private val cookieInterceptor by lazy {
        CookieInterceptor(baseUrl.substringAfter("://"), "ninemanga_list_num" to "1")
    }

    private val imgNiaddRegex = """img\d.\.niadd.com""".toRegex()
    private val imgRegex = Regex("""all_imgs_url\s*:\s*\[\s*([^]]*)\s*,\s*]""")
    private val redirectRegex = Regex("""window\.location\.href\s*=\s*["'](.*?)["']""")

    override val client: OkHttpClient by lazy {
        network.client.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (url.contains(imgNiaddRegex)) {
                    val newRequest = request.newBuilder()
                        .addHeader("Referer", "$baseUrl/")
                        .build()
                    return@addInterceptor chain.proceed(newRequest)
                }
                chain.proceed(request)
            }
            .addNetworkInterceptor(cookieInterceptor)
            .build()
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Accept-Language", "es-ES,es;q=0.9,en;q=0.8,gl;q=0.7")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) Gecko/20100101 Firefox/75")

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/list/New-Update/", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("dl.bookinfo").map { latestUpdatesFromElement(it) }
        val hasNextPage = document.select("ul.pageList > li:last-child > a.l").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    protected open fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        element.selectFirst("a.bookname")?.let {
            url = if (lang == "en") {
                it.attr("abs:href").substringAfter("ninemanga.com")
            } else {
                it.attr("abs:href").substringAfter(baseUrl)
            }
            title = it.text()
        }
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/category/index_$page.html", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("dl.bookinfo").map { popularMangaFromElement(it) }
        val hasNextPage = document.select("ul.pageList > li:last-child > a.l").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    protected open fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = if (lang in listOf("es", "ru", "fr")) query.substringBefore("'") else query
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()

        url.addQueryParameter("wd", q)
        url.addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is QueryCBEFilter -> url.addQueryParameter("name_sel", filter.toUriPart())
                is AuthorCBEFilter -> url.addQueryParameter("author_sel", filter.toUriPart())
                is AuthorFilter -> url.addQueryParameter("author", filter.state)
                is ArtistCBEFilter -> url.addQueryParameter("artist_sel", filter.toUriPart())
                is ArtistFilter -> url.addQueryParameter("artist", filter.state)
                is GenreList -> {
                    val genreInclude = filter.state.filter { it.isIncluded() }.joinToString("") { "${it.id}," }
                    val genreExclude = filter.state.filter { it.isExcluded() }.joinToString("") { "${it.id}," }
                    url.addQueryParameter("category_id", genreInclude)
                    url.addQueryParameter("out_category_id", genreExclude)
                }
                is CompletedFilter -> url.addQueryParameter("completed_series", filter.toUriPart())
                else -> {}
            }
        }

        url.addQueryParameter("type", "high")

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("dl.bookinfo").map { searchMangaFromElement(it) }
        val hasNextPage = document.select("ul.pageList > li:last-child > a.l").isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    protected open fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            document.selectFirst("div.bookintro")?.let {
                title = it.select("li > span:not([class])").text().removeSuffix(" Manga")
                genre = it.select("li[itemprop=genre] a").joinToString { e -> e.text() }
                author = it.select("li a[itemprop=author]").text()
                status = parseStatus(it.selectFirst("li a.red")?.text().orEmpty())
                description = it.select("p[itemprop=description]").text()
                thumbnail_url = it.selectFirst("img[itemprop=image]")?.attr("abs:src")
            }
        }
    }

    open fun parseStatus(status: String) = when (lang) {
        "es" -> when {
            status.contains("En curso") -> SManga.ONGOING
            status.contains("Completado") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        "pt-BR" -> when {
            status.contains("Em tradução") -> SManga.ONGOING
            status.contains("Completo") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        "ru" -> when {
            status.contains("завершенный") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        "de" -> when {
            status.contains("Laufende") -> SManga.ONGOING
            status.contains("Abgeschlossen") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        "it" -> when {
            status.contains("In corso") -> SManga.ONGOING
            status.contains("Completato") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        "fr" -> when {
            status.contains("En cours") -> SManga.ONGOING
            status.contains("Complété") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        else -> when {
            status.contains("Ongoing") -> SManga.ONGOING
            status.contains("Completed") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url + "?waring=1", headers) // Bypasses adult content warning
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaTitle = document.selectFirst("div.bookintro li > span:not([class])")?.text()?.removeSuffix(" Manga") ?: ""
        val titleForCleaning = "$mangaTitle "

        return document.select("ul.sub_vol_ul > li").map { element ->
            SChapter.create().apply {
                element.selectFirst("a.chapter_list_a")?.let {
                    name = it.text().replace(titleForCleaning, "", ignoreCase = true)
                    url = it.attr("abs:href").substringAfter(baseUrl).replace("%20", " ")
                }
                date_upload = parseChapterDate(element.select("span").text())
            }
        }
    }

    open fun parseChapterDate(date: String): Long {
        val dateWords = date.split(" ")

        if (dateWords.size == 3) {
            if (dateWords[1].contains(",")) {
                return dateFormat.tryParse(date)
            } else {
                val timeAgo = dateWords[0].toIntOrNull() ?: return 0L
                val calField = when (dateWords[1]) {
                    "minutos", "минут", "minuti", "minutes" -> Calendar.MINUTE
                    "horas", "hora", "часа", "Stunden", "ore", "heures" -> Calendar.HOUR
                    else -> return 0L
                }
                return Calendar.getInstance().apply {
                    add(calField, -timeAgo)
                }.timeInMillis
            }
        }
        return 0L
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (lang == "es") {
            val headers = headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .build()
            return GET(baseUrl + chapter.url, headers)
        }
        return super.pageListRequest(chapter)
    }

    override fun pageListParse(response: Response): List<Page> = pageListParse(response.asJsoup())

    open fun pageListParse(document: Document): List<Page> {
        if (lang == "es") {
            val serverUrl = document.selectFirst("section.section div.post-content-body > a")?.absUrl("href")
            if (serverUrl != null) {
                val serverHeaders = headers.newBuilder()
                    .set("Referer", document.baseUri())
                    .build()
                return pageListParse(client.newCall(GET(serverUrl, serverHeaders)).execute().asJsoup())
            }

            val redirectScript = document.selectFirst("body > script:containsData(window.location.href)")?.data()
            if (redirectScript != null) {
                val documentLocation = document.location()
                val redirectUrl = redirectRegex.find(redirectScript)
                    ?.groupValues?.get(1)
                    ?.let { path ->
                        path.toHttpUrlOrNull()
                            ?: documentLocation.toHttpUrl().newBuilder()
                                .encodedPath(path)
                                .build()
                    } ?: return defaultPageListParse(document)

                val headers = headers.newBuilder()
                    .set("Referer", documentLocation)
                    .build()

                val redirectedDocument = client.newCall(
                    GET(redirectUrl, headers),
                ).execute().asJsoup()

                return pageListParse(redirectedDocument)
            }

            val script = document.selectFirst("script:containsData(all_imgs_url)")?.data()
                ?: return defaultPageListParse(document)

            val images = imgRegex.find(script)?.groupValues?.get(1)
                ?.let { "[$it]".parseAs<List<String>>() }
                ?: throw Exception("Image list not found")

            return images.mapIndexed { idx, img ->
                Page(idx, imageUrl = img)
            }
        }
        return defaultPageListParse(document)
    }

    private fun defaultPageListParse(document: Document): List<Page> = document.select("select#page").first()?.select("option")?.mapIndexed { index, element ->
        Page(index, url = baseUrl + element.attr("value"))
    } ?: emptyList()

    override fun imageUrlParse(response: Response): String = imageUrlParse(response.asJsoup())

    open fun imageUrlParse(document: Document): String = document.select("div.pic_box img.manga_pic").first()?.attr("abs:src").orEmpty()

    override fun getFilterList() = FilterList(
        QueryCBEFilter(),
        AuthorCBEFilter(),
        AuthorFilter(),
        ArtistCBEFilter(),
        ArtistFilter(),
        GenreList(getGenreList()),
        CompletedFilter(),
    )

    open fun getGenreList(): List<Genre> = when (lang) {
        "es" -> esGenres
        "pt-BR" -> brGenres
        "ru" -> ruGenres
        "de" -> deGenres
        "it" -> itGenres
        "fr" -> frGenres
        else -> enGenres
    }
}
