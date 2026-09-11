package eu.kanade.tachiyomi.extension.fr.japscan

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.string
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Japscan :
    HttpSource(),
    ConfigurableSource {

    private val internalBaseUrl = "https://www.japscan.foo"

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val req = chain.request()
            if (req.url.host == JAPSCAN_CACHE_HOST) {
                val path = "/" + req.url.pathSegments.joinToString("/")
                val bytes = runCatching { File(path).readBytes() }.getOrNull()
                return@addInterceptor Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (bytes != null) 200 else 404)
                    .message(if (bytes != null) "OK" else "Not Found")
                    .body((bytes ?: ByteArray(0)).toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            val response = chain.proceed(req)
            if (response.code in listOf(403, 503)) {
                val isCf = response.header("Server")?.contains("cloudflare", ignoreCase = true) == true ||
                    response.header("cf-ray") != null ||
                    response.peekBody(1024).string().contains("Just a moment", ignoreCase = true)
                if (isCf) {
                    throw IOException("Cloudflare challenge détecté (HTTP ${response.code}). Ouvrez dans la WebView pour résoudre.")
                }
            }
            response
        }
        .rateLimit(1, 2.seconds)
        .build()

    private val captchaRegex = """window\.__captcha\s*=\s*\{\s*needed\s*:\s*true\s*,?""".toRegex()

    companion object {
        private const val JAPSCAN_CACHE_HOST = "japscan-cache.local"
        private const val CACHE_FILE_PREFIX = "japscan-"

        private val CHAPTER_PATH_TYPES = setOf("manga", "manhua", "manhwa", "bd", "comic")
        private val HIDDEN_STYLE_TOKENS = listOf(
            "display:none",
            "visibility:hidden",
            "opacity:0",
            "width:0",
            "height:0",
            "pointer-events:none",
            "clip-path:inset(100%",
            "clip-path:circle(0)",
            "clip:rect(0,0,0,0",
            "font-size:0",
            "text-indent:-",
        )

        // Match styles that visually remove an element while leaving it in the DOM:
        //  - large absolute offset (3+ digits) via top/bottom/left/right or `inset:` shorthand
        //  - `transform: translate / translateX / translateY / translated` with a 3+ digit offset
        //  - `transform: scale(0)` / `scale3d(0,...)` (collapsed to nothing)
        //  - `transform: matrix(0,0,0,0,...)` (also collapsed)
        //  - `max-width:0` / `max-height:0` (mirror of the existing width:0/height:0 tokens)
        // 3 digits is enough to be off-screen even with viewport units (200vh, 999vw, …)
        // while still tolerating fine adjustments like top:-1px or right:99px.
        private val OFFSCREEN_OFFSET_REGEX = Regex(
            """(?:top|bottom|left|right|inset):-?\d{3,}""" +
                """|transform:translate(?:3d|x|y)?\([^)]*-?\d{3,}""" +
                """|transform:scale(?:3d)?\(0[,)]""" +
                """|transform:matrix\(0,0,0,0""" +
                """|max-(?:width|height):0""",
        )
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)

        private const val SHOW_SPOILER_CHAPTERS_TITLE = "Les chapitres en Anglais ou non traduit sont upload en tant que \" Spoilers \" sur Japscan"
        private const val SHOW_SPOILER_CHAPTERS = "JAPSCAN_SPOILER_CHAPTERS"
        private val prefsEntries = arrayOf("Montrer uniquement les chapitres traduit en Français", "Montrer les chapitres spoiler")
        private val prefsEntryValues = arrayOf("hide", "show")
    }

    private fun chapterListPref() = preferences.getString(SHOW_SPOILER_CHAPTERS, "hide")

    override fun headersBuilder() = super.headersBuilder()
        .add("referer", "$internalBaseUrl/")

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET("$internalBaseUrl/mangas/?sort=popular&p=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val manga = document.select(".mangas-list .manga-block:not(:has(a[href='']))").map { element ->
            SManga.create().apply {
                element.select("a").first()!!.let {
                    setUrlWithoutDomain(it.attr("href"))
                    title = it.text()
                    thumbnail_url = it.selectFirst("img")?.attr("abs:data-src")
                }
            }
        }
        val hasNextPage = document.selectFirst(".pagination > li:last-child:not(.disabled)") != null
        return MangasPage(manga, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$internalBaseUrl/mangas/?sort=updated&p=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isEmpty()) {
            val url = internalBaseUrl.toHttpUrl().newBuilder().apply {
                addPathSegment("mangas")

                filters.forEach { filter ->
                    when (filter) {
                        is TextField -> addPathSegment(((page - 1) + filter.state.toInt()).toString())
                        is PageList -> addPathSegment(((page - 1) + filter.values[filter.state]).toString())
                        else -> {}
                    }
                }
            }.build()

            return GET(url, headers)
        } else {
            val formBody = FormBody.Builder()
                .add("search", query)
                .build()
            val searchHeaders = headers.newBuilder()
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            return POST("$internalBaseUrl/ls/", searchHeaders, formBody)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.request.url.pathSegments.first() == "ls") {
            val jsonResult = response.parseAs<JsonArray>()

            val mangaList = jsonResult.map { jsonEl -> searchMangaFromJson(jsonEl.jsonObject) }

            return MangasPage(mangaList, hasNextPage = false)
        }

        val baseUrlHost = internalBaseUrl.toHttpUrl().host
        val document = response.asJsoup()
        val manga = document
            .select("div.card div.p-2")
            .filter {
                // Filter out ads masquerading as search results
                it.select("p a").attr("abs:href").toHttpUrl().host == baseUrlHost
            }
            .map { element ->
                SManga.create().apply {
                    thumbnail_url = element.select("img").attr("abs:src")
                    element.select("p a").let {
                        title = it.text()
                        url = it.attr("href")
                    }
                }
            }
        val hasNextPage = document.selectFirst(".mangas-list .manga-block:not(:has(a[href='']))") != null

        return MangasPage(manga, hasNextPage)
    }

    private fun searchMangaFromJson(jsonObj: JsonObject): SManga = SManga.create().apply {
        url = jsonObj["url"]!!.string
        title = jsonObj["name"]!!.string
        thumbnail_url = internalBaseUrl + jsonObj["image"]!!.string
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(internalBaseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val infoElement = document.selectFirst("#main .card-body")!!
        val manga = SManga.create()

        manga.thumbnail_url = infoElement.selectFirst("img")?.attr("abs:src")

        val infoRows = infoElement.select(".row, .d-flex")
        infoRows.select("p").forEach { el ->
            when (el.select("span").text().trim()) {
                "Auteur(s):" -> manga.author = el.text().replace("Auteur(s):", "").trim()

                "Artiste(s):" -> manga.artist = el.text().replace("Artiste(s):", "").trim()

                "Genre(s):" -> manga.genre = el.text().replace("Genre(s):", "").trim()

                "Statut:" -> manga.status = el.text().replace("Statut:", "").trim().let {
                    parseStatus(it)
                }
            }
        }
        manga.description = infoElement.selectFirst("div:contains(Synopsis) + p")?.ownText().orEmpty()

        return manga
    }

    private fun parseStatus(status: String) = status.lowercase().let {
        when {
            it.contains("en cours") -> SManga.ONGOING
            it.contains("terminé") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = internalBaseUrl + chapter.url

    override fun chapterListRequest(manga: SManga): Request = GET(internalBaseUrl + manga.url, headers)

    private fun chapterListSelector() = "#list_chapters > div.collapse > div.list_chapters" +
        if (chapterListPref() == "hide") {
            ":not(:has(.badge:contains(SPOILER),.badge:contains(RAW),.badge:contains(VUS)))"
        } else {
            ""
        }
    // JapScan sometimes uploads some "spoiler preview" chapters, containing 2 or 3 untranslated pictures taken from a raw. Sometimes they also upload full RAWs/US versions and replace them with a translation as soon as available.
    // Those have a span.badge "SPOILER" or "RAW". The additional pseudo selector makes sure to exclude these from the chapter list.

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val mangaSlug = extractMangaSlug(response.request.url)
        val chapters = document.select(chapterListSelector()).mapNotNull { el ->
            runCatching { parseChapter(el, mangaSlug) }.getOrNull()
        }
        return filterOutlierChapters(chapters)
    }

    // Defense in depth: if a honeypot ever slips past the per-row hidden-style
    // heuristics, its URL number is wildly out of range (e.g. 483181 vs. real 1181).
    // Drop the upper cluster when consecutive chapter numbers jump by more than 1000.
    private fun filterOutlierChapters(chapters: List<SChapter>): List<SChapter> {
        val withNum = chapters.mapNotNull { ch ->
            val n = ch.url.trimEnd('/').substringAfterLast('/').toLongOrNull() ?: return@mapNotNull null
            ch to n
        }
        if (withNum.size < 2) return chapters
        val sorted = withNum.sortedBy { it.second }
        var gapIdx = -1
        var gapSize = 0L
        for (i in 1 until sorted.size) {
            val g = sorted[i].second - sorted[i - 1].second
            if (g > gapSize) {
                gapSize = g
                gapIdx = i
            }
        }
        if (gapSize <= 1000) return chapters
        val keep = sorted.take(gapIdx).map { it.first }.toSet()
        return chapters.filter { it in keep }
    }

    private fun extractMangaSlug(url: okhttp3.HttpUrl): String? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val typeIdx = segments.indexOfFirst { it in CHAPTER_PATH_TYPES }
        if (typeIdx == -1 || typeIdx + 1 >= segments.size) return null
        return segments[typeIdx + 1].takeIf { it.isNotEmpty() }
    }

    private fun isHidden(el: Element): Boolean {
        if (el.hasClass("d-none")) return true
        if (el.hasAttr("hidden")) return true
        if (el.attr("aria-hidden").equals("true", ignoreCase = true)) return true
        val style = el.attr("style").replace(" ", "").lowercase()
        if (HIDDEN_STYLE_TOKENS.any { style.contains(it) }) return true
        if (OFFSCREEN_OFFSET_REGEX.containsMatchIn(style)) return true
        return false
    }

    private fun isHiddenWithin(el: Element, root: Element): Boolean {
        var cur: Element? = el
        while (cur != null && cur !== root) {
            if (isHidden(cur)) return true
            cur = cur.parent()
        }
        return false
    }

    private fun parseChapter(element: Element, mangaSlug: String?): SChapter {
        // Only search for a tag with any attribute containing manga/manhua/manhwa.
        // Skip elements that are visually hidden — Japscan hides honeypots with
        // class="d-none", inline display/visibility/opacity:0, zero size, or by
        // positioning them way off-screen. The visible chapter row never carries
        // any of these, so to evade detection Japscan would have to make the
        // honeypots visible to humans too.
        val allUrlPairs = (element.getElementsContainingText("Chapitre") + element.getElementsContainingText("Volume"))
            .filterNot { isHiddenWithin(it, element) }
            .mapNotNull { el ->
                // Find the first attribute whose value matches the chapter URL pattern
                val attrMatch = el.attributes().asList().firstOrNull { attr ->
                    val value = attr.value
                    value.startsWith("/manga/") || value.startsWith("/manhua/") || value.startsWith("/manhwa/") || value.startsWith("/bd/") || value.startsWith("/comic/")
                }
                if (attrMatch != null) {
                    val name = el.ownText().ifBlank { el.text() }
                    // Mark if the attribute is not "href"
                    val isNonHref = attrMatch.key != "href"
                    Triple(name, attrMatch.value, isNonHref)
                } else {
                    null
                }
            }
            .distinctBy { it.second }

        // Filter out anti-scraping honeypots by binding name, slug and URL number together:
        // a real chapter URL is /<type>/<mangaSlug>/<chapterNum>/, with the same slug as the
        // manga page and a chapter number that appears in the chapter's name ("Chapitre N: ...").
        // Stripping non-digits from the name handles half-chapters like "Chapitre 1100.5" + /11005/.
        // Honeypots use a different slug (e.g. /manga/cv/N/) with sequential numbers that match a
        // fake "Chapitre N" label, so name/number alone is not enough — slug check is what stops them.
        val filtered = allUrlPairs
            .filter { (name, url, _) ->
                val segments = url.split('/').filter { it.isNotEmpty() }
                if (segments.size != 3) return@filter false
                if (segments[0] !in CHAPTER_PATH_TYPES) return@filter false
                if (mangaSlug != null && segments[1] != mangaSlug) return@filter false
                val urlNum = url.trimEnd('/').substringAfterLast('/')
                if (!urlNum.all { it.isDigit() }) return@filter false
                if (urlNum.length > 1 && urlNum.startsWith('0')) return@filter false
                val chapterNum = Regex("""(?i)chapitre\s+([\d.]+)""").find(name)
                    ?.groupValues?.get(1)?.replace(".", "")
                    ?: name.split(Regex("[^0-9.]+")).lastOrNull { it.isNotEmpty() }?.replace(".", "")
                    ?: return@filter false
                chapterNum == urlNum
            }

        // Fall back to the unfiltered list in case the heuristics are too aggressive.
        // Defense in depth: when the slug filter is unavailable, prefer the longest URL — real
        // slugs (e.g. "one-piece") are usually longer than honeypot slugs (e.g. "cv").
        val urlPairs = (filtered.ifEmpty { allUrlPairs })
            .sortedWith(
                compareByDescending<Triple<String, String, Boolean>> { it.third }
                    .thenByDescending { it.second.length },
            ) // Prefer non-href first, then longer URLs
            .map { Pair(it.first, it.second) }

        val foundPair = urlPairs.firstOrNull()
            ?: throw Exception("Impossible de trouver l'URL du chapitre")

        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain(foundPair.second)
        chapter.name = foundPair.first
        chapter.date_upload = element.selectFirst("span")?.text()?.let { parseChapterDate(it) } ?: 0L
        return chapter
    }

    private fun parseChapterDate(date: String) = dateFormat.tryParse(date)

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val interfaceName = randomString()
        val context = Injekt.get<Application>()
        val isReader = Exception().stackTrace.any { it.className.contains("reader") }

        val handler = Handler(Looper.getMainLooper())
        val latch = CountDownLatch(1)
        sweepPageCache(context.cacheDir)
        var webView: WebView? = null
        var request: Response = client.newCall(GET("$internalBaseUrl${chapter.url}", headers)).execute()
        var pageContent = request.body.string()

        Log.d("JapscanDebug", "fetchPageList for ${chapter.url} - page length: ${pageContent.length}")

        // Attempt automatic challenge solving
        val stripsArrayRegex = """(?:strips|"[a-f0-9]{6}")\s*:\s*(\[[^\]]*\])""".toRegex()
        val elementRegex = """\{\s*"uuid"\s*:\s*"([^"]+)"\s*,\s*"src"\s*:\s*"([^"]*)"\s*\}""".toRegex()
        val tokenRegex = """(?:token|"[a-f0-9]{6}")\s*:\s*"([0-9a-fA-F]{64})"""".toRegex()

        var autoCaptchaTry = 0
        while (autoCaptchaTry < 3) {
            val stripsMatch = stripsArrayRegex.find(pageContent)
            val stripsContent = stripsMatch?.groupValues?.get(1).orEmpty()
            val tokenMatch = tokenRegex.find(pageContent)

            if (stripsContent.isNotEmpty() && tokenMatch != null) {
                Log.d("JapscanDebug", "Captcha detected on try #$autoCaptchaTry - token found")
                val items = elementRegex.findAll(stripsContent).map {
                    val uuid = it.groupValues[1]
                    val src = it.groupValues[2]
                    uuid to src
                }.toList()

                if (items.size == 4) {
                    val solved = runCatching {
                        val answer = orderUuidsByImageVerticality(items)
                        Log.d("JapscanDebug", "Captcha ordered UUIDs: $answer")
                        val token = tokenMatch.groupValues[1]
                        val multipartBody = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                            .addFormDataPart("token", token)
                            .addFormDataPart("answer", answer.toJsonString())
                            .build()

                        val captchaRequest = client.newCall(
                            POST(
                                "$internalBaseUrl/validate-captcha/",
                                headers = headers.newBuilder()
                                    .add("Referer", "$internalBaseUrl${chapter.url}")
                                    .build(),
                                body = multipartBody,
                            ),
                        ).execute()
                        val captchaResponseBody = captchaRequest.body.string()
                        Log.d("JapscanDebug", "Captcha response: $captchaResponseBody")
                        captchaResponseBody.contains(""""success":\s*true""".toRegex())
                    }.getOrDefault(false)

                    if (solved) {
                        Log.d("JapscanDebug", "Captcha solved successfully! Reloading page...")
                        request = client.newCall(GET("$internalBaseUrl${chapter.url}", headers)).execute()
                        pageContent = request.body.string()
                        if (!pageContent.contains(captchaRegex)) {
                            Log.d("JapscanDebug", "Captcha cleared after reload!")
                            break
                        }
                    } else {
                        Log.d("JapscanDebug", "Captcha solving attempt #$autoCaptchaTry failed. Re-fetching chapter to get a new captcha...")
                        // Re-fetch chapter page so the next try operates on a brand new challenge image
                        request = client.newCall(GET("$internalBaseUrl${chapter.url}", headers)).execute()
                        pageContent = request.body.string()
                    }
                } else {
                    Log.d("JapscanDebug", "Captcha items count is not 4: ${items.size}")
                }
                autoCaptchaTry++
            } else {
                Log.d("JapscanDebug", "No strips or token detected in page content.")
                break
            }
        }

        val matchResult = captchaRegex.find(pageContent)
        Log.d("JapscanDebug", "captchaRegex match: ${matchResult != null}")
        if (matchResult != null) {
            val idx = pageContent.indexOf("window.__captcha")
            if (idx != -1) {
                val snippet = pageContent.substring(idx, minOf(pageContent.length, idx + 1000))
                Log.d("JapscanDebug", "Captcha snippet: $snippet")
            }
        }

        if (matchResult != null) {
            try {
                val intent = Intent().apply {
                    component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("url_key", "$internalBaseUrl${chapter.url}")
                    putExtra("source_key", id)
                    putExtra("title_key", "Résolvez le captcha, fermez la Webview et réouvrez le chapitre.")
                }

                context.startActivity(intent)
            } catch (_: Exception) {
                // Suwayomi etc.
                throw Exception("Résolvez le captcha de ce chapitre depuis la WebView et réouvrez le chapitre.")
            }
            var captchaWait = 0
            while (captchaWait < 15) {
                Thread.sleep(5000)
                request = client.newCall(GET("$internalBaseUrl${chapter.url}", headers)).execute()
                pageContent = request.body.string()
                val isGood = captchaRegex.find(pageContent)
                if (isGood == null) {
                    val closeIntent = Intent().apply {
                        val targetClass = if (isReader) {
                            "eu.kanade.tachiyomi.ui.reader.ReaderActivity"
                        } else {
                            "eu.kanade.tachiyomi.ui.main.MainActivity"
                        }
                        component = ComponentName(context, targetClass)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(closeIntent)
                    break
                } else {
                    captchaWait++
                }
            }
            if (captchaWait >= 15) {
                throw Exception("Résolvez le captcha, fermez la Webview et réouvrez le chapitre.")
            }
        }

        val urlSegment = chapter.url.trimStart('/').substringBefore('/').lowercase()
        val isWebtoon = urlSegment == "manhwa" || urlSegment == "manhua"
        val jsInterface = JsInterface(latch, context.cacheDir)

        handler.post {
            // Synchronize cookies from OkHttp client to Android WebView CookieManager
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val httpUrl = "$internalBaseUrl${chapter.url}".toHttpUrl()
            val okHttpCookies = client.cookieJar.loadForRequest(httpUrl)
            for (cookie in okHttpCookies) {
                cookieManager.setCookie(internalBaseUrl, "${cookie.name}=${cookie.value}; path=/; domain=${httpUrl.host}")
            }
            cookieManager.flush()

            val innerWv = WebView(context)
            webView = innerWv
            cookieManager.setAcceptThirdPartyCookies(innerWv, true)

            innerWv.settings.domStorageEnabled = true
            innerWv.settings.javaScriptEnabled = true
            innerWv.settings.blockNetworkImage = false
            innerWv.settings.userAgentString = headers["User-Agent"]
            innerWv.addJavascriptInterface(jsInterface, interfaceName)

            innerWv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d("JapscanDebug", "[Console] ${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
                    return true
                }
            }

            innerWv.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    Log.d("JapscanDebug", "WebView onPageStarted: $url")

                    // Satisfy anti-adblock check
                    view?.evaluateJavascript(
                        """
                            (function(){
                                if (window.aclib) return;
                                window.aclib = {
                                    runPop: function runPop(){},
                                    runBanner: function runBanner(){},
                                    runNative: function runNative(){},
                                    runInPagePush: function runInPagePush(){},
                                    isShowingPop: false,
                                };
                            })();
                        """.trimIndent(),
                        null,
                    )

                    // Blob queue hook: captures each decrypted image blob exactly once
                    view?.evaluateJavascript(
                        """
                            (function(){
                                if (window.__japscanHooked) return;
                                window.__japscanHooked = true;
                                window.__japscanBlobQueue = [];
                                var _seenBlobs = new WeakSet();
                                var _orig = URL.createObjectURL.bind(URL);
                                URL.createObjectURL = function(obj){
                                    var u = _orig(obj);
                                    try {
                                        if (obj && obj.type && /^image\//.test(obj.type)) {
                                            if (!_seenBlobs.has(obj)) {
                                                _seenBlobs.add(obj);
                                                window.__japscanBlobQueue.push(u);
                                            }
                                        }
                                    } catch(e) {}
                                    return u;
                                };
                                try {
                                    URL.createObjectURL.toString = function(){
                                        return 'function createObjectURL() { [native code] }';
                                    };
                                } catch(e) {}
                            })();
                        """.trimIndent(),
                        null,
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d("JapscanDebug", "WebView onPageFinished: $url")
                    view?.onResume()
                    view?.resumeTimers()

                    // Driver: drains __japscanBlobQueue and navigates/scrolls to ensure all pages render
                    view?.evaluateJavascript(
                        """
                            (async function(){
                                if (window.__japscanDriverStarted) return;
                                window.__japscanDriverStarted = true;
                                var sleep = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };

                                async function saveBlobUrl(u){
                                    if (!u) return false;
                                    try {
                                        var r = await fetch(u);
                                        var b = await r.blob();
                                        var d = await new Promise(function(res, rej){
                                            var fr = new FileReader();
                                            fr.onload = function(){ res(fr.result); };
                                            fr.onerror = rej;
                                            fr.readAsDataURL(b);
                                        });
                                        if (typeof d === 'string' && d.indexOf('data:image/') === 0) {
                                            window.$interfaceName.savePage(d);
                                            return true;
                                        }
                                    } catch(e) {
                                        console.log('[japscan] saveBlobUrl failed: ' + e);
                                    } finally {
                                        try { URL.revokeObjectURL(u); } catch(e) {}
                                    }
                                    return false;
                                }

                                async function drainQueue(){
                                    var count = 0;
                                    while (window.__japscanBlobQueue && window.__japscanBlobQueue.length > 0) {
                                        var u = window.__japscanBlobQueue.shift();
                                        var ok = await saveBlobUrl(u);
                                        if (ok) count++;
                                    }
                                    return count;
                                }

                                var isWebtoon = $isWebtoon;
                                window.$interfaceName.log('driver started, isWebtoon=' + isWebtoon + ', url=' + window.location.href + ', title=' + document.title);

                                if (isWebtoon) {
                                    var items = [];
                                    for (var w = 0; w < 40; w++) {
                                        var fullReader = document.getElementById('full-reader');
                                        if (fullReader && fullReader.children.length > 0) {
                                            items = Array.from(fullReader.children);
                                            break;
                                        }
                                        await sleep(250);
                                    }
                                    var total = items.length;
                                    window.$interfaceName.log('webtoon mode, items=' + total);
                                    if (total === 0) {
                                        window.__japscanDriverStarted = false;
                                        return;
                                    }

                                    var savedSoFar = 0;
                                    function getSavedCount() {
                                        try { return window.$interfaceName.getSavedCount(); } catch(e) { return savedSoFar; }
                                    }

                                    // Trigger lazy rendering by scrolling down through all items
                                    for (var i = 0; i < items.length; i++) {
                                        try {
                                            items[i].scrollIntoView({ block: 'center', behavior: 'instant' });
                                        } catch(e) {}
                                        var drained = await drainQueue();
                                        savedSoFar += drained;
                                        if (getSavedCount() >= total) break;
                                        await sleep(200);
                                    }

                                    // Wait for any remaining items to finish processing
                                    var idleRounds = 0;
                                    while (idleRounds < 6 && getSavedCount() < total) {
                                        var newSaved = await drainQueue();
                                        if (newSaved > 0) {
                                            savedSoFar += newSaved;
                                            idleRounds = 0;
                                        } else {
                                            idleRounds++;
                                        }
                                        await sleep(400);
                                    }

                                    console.log('[japscan] webtoon done, saved=' + getSavedCount());
                                    try { window.$interfaceName.passDone(); } catch(e) {}
                                    return;
                                }

                                // Paginated mode for standard mangas
                                var sel = document.getElementById('pages');
                                var total = sel ? sel.options.length : 1;
                                var nextBtn = document.getElementById('block-right');
                                var prevBtn = document.getElementById('block-left');
                                console.log('[japscan] paginated mode, total = ' + total);

                                function nav(btn, fallbackKey){
                                    try {
                                        if (btn) { btn.click(); return; }
                                        document.dispatchEvent(new KeyboardEvent('keydown', {
                                            key: fallbackKey, code: fallbackKey,
                                            which: fallbackKey === 'ArrowRight' ? 39 : 37,
                                            keyCode: fallbackKey === 'ArrowRight' ? 39 : 37,
                                            bubbles: true,
                                        }));
                                    } catch(e) {
                                        console.log('[japscan] nav failed: ' + e);
                                    }
                                }

                                async function waitForNextBlob(timeoutMs){
                                    var w = 0;
                                    while ((!window.__japscanBlobQueue || window.__japscanBlobQueue.length === 0) && w < timeoutMs) {
                                        await sleep(100); w += 100;
                                    }
                                    if (!window.__japscanBlobQueue || window.__japscanBlobQueue.length === 0) return null;
                                    return window.__japscanBlobQueue.shift();
                                }

                                await waitForNextBlob(10000);
                                window.__japscanBlobQueue = [];

                                nav(nextBtn, 'ArrowRight');
                                await waitForNextBlob(8000);
                                window.__japscanBlobQueue = [];

                                nav(prevBtn, 'ArrowLeft');
                                var u0 = await waitForNextBlob(8000);
                                if (total > 1) nav(nextBtn, 'ArrowRight');
                                if (u0) await saveBlobUrl(u0);

                                for (var i = 1; i < total; i++) {
                                    var u = await waitForNextBlob(8000);
                                    if (i < total - 1) nav(nextBtn, 'ArrowRight');
                                    if (u) await saveBlobUrl(u);
                                }

                                await drainQueue();
                                console.log('[japscan] paginated done');
                                try {
                                    window.$interfaceName.passDone();
                                } catch(e) {
                                    console.log('[japscan] passDone failed: ' + e);
                                }
                            })();
                        """.trimIndent(),
                        null,
                    )
                }
            }

            val initialUrl = if (isWebtoon && chapter.url.endsWith("/")) {
                "$internalBaseUrl${chapter.url}1.html"
            } else {
                "$internalBaseUrl${chapter.url}"
            }
            innerWv.loadUrl(
                initialUrl,
                headers.toMap(),
            )
        }

        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
        var done = false
        while (!done && System.currentTimeMillis() < deadline) {
            done = latch.await(5, TimeUnit.SECONDS)
            if (!done && System.currentTimeMillis() - jsInterface.lastActivity > 45_000L) {
                Log.d("JapscanDebug", "No page saved in 45s, timing out")
                break
            }
        }
        handler.post { webView?.destroy() }

        if (latch.count == 1L && jsInterface.snapshot().isEmpty()) {
            throw Exception("Erreur lors de la récupération des pages")
        }

        val pages = jsInterface.snapshot().mapIndexed { i, path ->
            Page(i, imageUrl = "https://$JAPSCAN_CACHE_HOST$path")
        }
        Log.d("JapscanDebug", "Delivering ${pages.size} verified pages to reader!")
        return Observable.just(pages)
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // Filters
    private class TextField(name: String) : Filter.Text(name)

    private class PageList(pages: Array<Int>) : Filter.Select<Int>("Page #", arrayOf(0, *pages))

    // Prefs
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val chapterListPref = ListPreference(screen.context).apply {
            key = SHOW_SPOILER_CHAPTERS
            title = SHOW_SPOILER_CHAPTERS_TITLE
            entries = prefsEntries
            entryValues = prefsEntryValues
            summary = "%s"
            setDefaultValue("hide")
        }
        screen.addPreference(chapterListPref)
    }

    private fun sweepPageCache(cacheDir: File) {
        val cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
        cacheDir.listFiles()?.forEach {
            if (it.name.startsWith(CACHE_FILE_PREFIX) && it.lastModified() < cutoff) it.delete()
        }
    }

    private fun randomString(length: Int = 10): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }

    internal class JsInterface(
        private val latch: CountDownLatch,
        private val cacheDir: File,
    ) {
        @Volatile
        var lastActivity: Long = System.currentTimeMillis()
            private set

        private val savedPaths = mutableListOf<String>()
        private val savedHashes = mutableSetOf<String>()
        private val sessionTag = "$CACHE_FILE_PREFIX${System.currentTimeMillis()}"

        fun snapshot(): List<String> = synchronized(savedPaths) { savedPaths.toList() }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun savePage(dataUri: String) {
            lastActivity = System.currentTimeMillis()
            try {
                val commaIdx = dataUri.indexOf(',')
                if (commaIdx <= 0) return
                val base64 = dataUri.substring(commaIdx + 1)
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val md = MessageDigest.getInstance("SHA-256")
                val hash = md.digest(bytes).joinToString("") { "%02x".format(it) }
                synchronized(savedPaths) {
                    if (!savedHashes.add(hash)) {
                        Log.d("JapscanDebug", "Skipping duplicate page (hash: ${hash.take(8)})")
                        return
                    }
                    val file = File(cacheDir, "$sessionTag-${savedPaths.size}.bin")
                    file.writeBytes(bytes)
                    savedPaths.add(file.absolutePath)
                    Log.d("JapscanDebug", "Saved page #${savedPaths.size} to ${file.absolutePath} (${bytes.size} bytes)")
                }
            } catch (e: Exception) {
                Log.e("JapscanDebug", "Failed to save page", e)
            }
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun getSavedCount(): Int = synchronized(savedPaths) { savedPaths.size }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun log(msg: String) {
            Log.d("JapscanDebug", "[FromJS] $msg")
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passDone() {
            Log.d("JapscanDebug", "passDone received! Total pages saved: ${savedPaths.size}")
            latch.countDown()
        }
    }
}
