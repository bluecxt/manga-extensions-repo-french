package eu.kanade.tachiyomi.extension.fr.japscan

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.source.SMangaUpdate
import keiyoushi.utils.applicationContext
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParseDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * [runCatching] with coroutine cancellation rethrown.
 *
 * Swallowing a [CancellationException] turns a cancelled job into a silent failure: the capture
 * keeps running against a WebView that is being destroyed, and the caller never learns that its
 * job was cancelled.
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

@Source
abstract class Japscan :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    // One WebView for the whole process. Destroying it after every chapter left Chromium in a state
    // where the next view got a reader shell with no pages at all: only the first capture after a
    // cold start ever succeeded. Reusing it also keeps the renderer warm between chapters.
    @Volatile
    private var readerWebView: WebView? = null

    @Volatile
    private var captureRequest: CaptureRequest? = null

    /** Final URL when the chapter redirected elsewhere, which means it no longer exists. */
    @Volatile
    private var redirectedTo: String? = null

    /** Wall clock until which the captcha endpoint refuses validations; 0 when it accepts them. */
    @Volatile
    private var captchaBlockedUntil: Long = 0L

    // Which same-origin requests the reader makes during the current capture: the page list has to
    // come from one of them, so seeing them (or not) tells a dead script from a blocked endpoint.
    private val seenRequests: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var readerInterfaceName: String? = null

    private val readerJsInterface by lazy { JsInterface(applicationContext.cacheDir) }

    private val idleHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Everything one capture needs; the reused WebView reads it from [captureRequest]. */
    private class CaptureRequest(
        val chapterUrl: String,
        val urlSegment: String,
        val isWebtoon: Boolean,
        val interfaceName: String,
        val uploadUrl: String,
    )

    // Pages are captured from the reader's canvases and spooled to the cache dir; their imageUrl
    // points at a sentinel host that this interceptor serves from disk, ahead of the rate limiter.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host != CACHE_HOST) return@addInterceptor chain.proceed(request)
            // encodedPath is exactly what imageUrl carried: no pathSegments list, no join, and a
            // single existence check instead of two.
            val file = File(request.url.encodedPath)
            val bytes = if (file.exists()) file.readBytes() else null
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (bytes != null) 200 else 404)
                .message(if (bytes != null) "OK" else "Not Found")
                .body((bytes ?: ByteArray(0)).toResponseBody("image/jpeg".toMediaType()))
                .build()
        }
        rateLimit(1, 2.seconds)
    }

    // Sometimes an adblock blocker will pop up, preventing the user from opening
    // a cloudflare protected page
    override fun getHomeUrl() = "$baseUrl/mangas/?sort=popular&p=1"

    override suspend fun getPopularManga(page: Int) = parseMangaList(client.get("$baseUrl/mangas/?sort=popular&p=$page", Fetch.NONE).asJsoup())

    override suspend fun getLatestUpdates(page: Int) = parseMangaList(client.get("$baseUrl/mangas/?sort=updated&p=$page", Fetch.NONE).asJsoup())

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select(".manga-block:not(:has(a[href='']))").map { element ->
            SManga.create().apply {
                element.selectFirst("a")!!.let {
                    setUrlWithoutDomain(it.absUrl("href"))
                    title = it.text()
                    thumbnail_url = it.selectFirst("img")?.absUrl("data-src")
                }
            }
        }
        val hasNextPage = document.selectFirst(".pagination > li:last-child:not(.disabled)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)

        // Two catalogues, two listings: /mangas/ holds manga, manhwa and manhua while /bds/ is the
        // Bande Dessinée catalogue. Both use the same markup and accept ?search=, so query both and
        // de-duplicate, exactly like the server does.
        val mangas = parseMangaList(
            client.get(searchUrl("mangas", query, page), Fetch.NONE).asJsoup(),
        )
        val bds = runCatchingCancellable {
            parseMangaList(client.get(searchUrl("bds", query, page), Fetch.NONE).asJsoup())
        }.getOrNull()

        val items = (mangas.mangas + (bds?.mangas ?: emptyList())).distinctBy { it.url }
        return MangasPage(items, mangas.hasNextPage || bds?.hasNextPage == true)
    }

    /** Builds a `/<catalogue>/?search=…&p=…` listing URL for [catalogue]. */
    private fun searchUrl(catalogue: String, query: String, page: Int): HttpUrl = "$baseUrl/$catalogue/".toHttpUrl().newBuilder()
        .addQueryParameter("search", query)
        .addQueryParameter("p", page.toString())
        .build()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments.filter(String::isNotEmpty)
        if (segments.size < 2 || segments[0] !in CHAPTER_PATH_TYPES) return null
        val mangaUrl = "/${segments[0]}/${segments[1]}/"
        val document = client.get(baseUrl + mangaUrl, Fetch.NONE).asJsoup()
        return parseMangaDetails(document).apply {
            this.url = mangaUrl
            title = document.selectFirst("#main .card-body h1")!!.text()
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(baseUrl + manga.url, Fetch.NONE)
        val mangaSlug = extractMangaSlug(response.request.url)
        val document = response.asJsoup()
        val details = parseMangaDetails(document).apply {
            url = manga.url
            title = manga.title
        }
        val chapterList = document.select(chapterListSelector()).mapNotNull { el ->
            runCatching { parseChapter(el, mangaSlug) }.getOrNull()
        }
        return SMangaUpdate(details, filterOutlierChapters(chapterList))
    }

    private fun parseMangaDetails(document: Document) = SManga.create().apply {
        val infoElement = document.selectFirst("#main .card-body")!!
        thumbnail_url = infoElement.selectFirst("img")?.absUrl("src")
        infoElement.select(".row, .d-flex").select("p").forEach { el ->
            when (el.select("span").text()) {
                "Auteur(s):" -> author = el.text().removePrefix("Auteur(s):").trim()
                "Artiste(s):" -> artist = el.text().removePrefix("Artiste(s):").trim()
                "Genre(s):" -> genre = el.text().removePrefix("Genre(s):").trim()
                "Statut:" -> status = parseStatus(el.text().removePrefix("Statut:"))
            }
        }
        description = infoElement.selectFirst("div:contains(Synopsis) + p")?.ownText()
    }

    private fun parseStatus(status: String) = status.lowercase().let {
        when {
            it.contains("en cours") -> SManga.ONGOING
            it.contains("terminé") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // JapScan sometimes uploads some "spoiler preview" chapters, containing 2 or 3 untranslated pictures taken from a raw. Sometimes they also upload full RAWs/US versions and replace them with a translation as soon as available.
    // Those have a span.badge "SPOILER" or "RAW". The additional pseudo selector makes sure to exclude these from the chapter list.
    private fun chapterListSelector() = "#list_chapters > div.collapse > div.list_chapters" +
        if (preferences.getString(SHOW_SPOILER_CHAPTERS, "hide") == "hide") {
            ":not(:has(.badge:contains(SPOILER),.badge:contains(RAW),.badge:contains(VUS)))"
        } else {
            ""
        }

    // Backstop for a honeypot that clears the slug/number binding in parseChapter: its number
    // comes out wildly out of range (observed: 483181 among real 1174..1181), so cap on the median.
    // Compare chapter_number, not the URL id: "Chapitre 1100.5" is /11005/.
    private fun filterOutlierChapters(chapters: List<SChapter>): List<SChapter> {
        val nums = chapters.map { it.chapter_number }.filter { it >= 0f }.sorted()
        if (nums.size < 3) return chapters
        val ceiling = nums[nums.size / 2] * 10 + 100
        return chapters.filter { it.chapter_number <= ceiling }
    }

    private fun extractMangaSlug(url: HttpUrl): String? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val typeIdx = segments.indexOfFirst { it in CHAPTER_PATH_TYPES }
        if (typeIdx == -1 || typeIdx + 1 >= segments.size) return null
        return segments[typeIdx + 1]
    }

    private fun isHidden(el: Element): Boolean {
        if (el.hasClass("d-none")) return true
        if (el.hasAttr("hidden")) return true
        if (el.attr("aria-hidden").equals("true", ignoreCase = true)) return true
        if (!el.hasAttr("style")) return false
        val style = el.attr("style").replace(" ", "").lowercase()
        return HIDDEN_STYLE_TOKENS.any { style.contains(it) } || HIDDEN_STYLE_REGEX.containsMatchIn(style)
    }

    private fun isHiddenWithin(el: Element, root: Element): Boolean {
        var cur: Element? = el
        while (cur != null && cur !== root) {
            if (isHidden(cur)) return true
            cur = cur.parent()
        }
        return false
    }

    // Japscan hides honeypot links in each row (d-none, zero size, off-screen, ...) and puts the
    // real URL in a random non-href attribute. Only visible elements are considered, and the URL
    // must be /<type>/<mangaSlug>/<N>/ with N matching the chapter number in the name.
    private fun parseChapter(element: Element, mangaSlug: String?): SChapter {
        val allUrlPairs = (element.getElementsContainingText("Chapitre") + element.getElementsContainingText("Volume"))
            .filterNot { isHiddenWithin(it, element) }
            .mapNotNull { el ->
                val attrMatch = el.attributes().asList().firstOrNull { attr ->
                    CHAPTER_PATH_TYPES.any { attr.value.startsWith("/$it/") }
                }
                attrMatch?.let { Pair(el.ownText().ifBlank { el.text() }, it.value) }
            }
            .distinctBy { it.second }

        val filtered = allUrlPairs.filter { (name, url) ->
            val segments = url.split('/').filter { it.isNotEmpty() }
            if (segments.size != 3) return@filter false
            if (segments[0] !in CHAPTER_PATH_TYPES) return@filter false
            if (mangaSlug != null && segments[1] != mangaSlug) return@filter false
            val urlNum = segments[2]
            if (!urlNum.all { it.isDigit() }) return@filter false
            if (urlNum.length > 1 && urlNum.startsWith('0')) return@filter false
            val chapterNum = CHAPTER_NUM_REGEX.find(name)?.groupValues?.get(1)?.replace(".", "")
                ?: name.split(NON_NUMBER_REGEX).lastOrNull { it.isNotEmpty() }?.replace(".", "")
                ?: return@filter false
            chapterNum == urlNum
        }

        // Fall back to the unfiltered list in case the heuristics are too aggressive, preferring
        // the longest URL: real slugs are usually longer than honeypot slugs (e.g. "cv").
        val (name, url) = filtered.firstOrNull()
            ?: allUrlPairs.maxByOrNull { it.second.length }
            ?: throw Exception("Impossible de trouver l'URL du chapitre")

        return SChapter.create().apply {
            this.url = url
            this.name = name
            chapter_number = CHAPTER_NUM_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            date_upload = DATE_FORMAT.tryParseDate(element.selectFirst("span.float-right")?.text(), PARIS)
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Must be read before the first suspension point, while the caller's frames are on the stack.
        val isReader = Exception().stackTrace.any { it.className.contains("reader") }
        val context = applicationContext
        val chapterUrl = baseUrl + chapter.url
        sweepPageCache(context.cacheDir)
        Log.d("JapscanReader", "getPageList start: url=$chapterUrl")

        // cachedPageList only returns an entry whose files are still spooled and that is younger
        // than PAGE_LIST_CACHE_TTL_MS, and rememberPageList only stores a completed capture, so a
        // hit is always a full chapter.
        cachedPageList(chapterUrl)?.let { paths ->
            Log.d("JapscanReader", "getPageList cache hit: ${paths.size} pages for $chapterUrl")
            return paths.mapIndexed { i, path -> Page(i, imageUrl = "https://$CACHE_HOST$path") }
        }

        // manhwa/manhua usually use the long-strip reader, everything else the paginated one, but
        // the URL segment is only a hint: some manhua chapters mount the paginated reader. The mode
        // decides which hooks are installed before the page loads, so a mismatch is retried once
        // with the other mode instead of returning an empty chapter.
        val urlSegment = chapter.url.trimStart('/').substringBefore('/').lowercase()
        var isWebtoon = urlSegment == "manhwa" || urlSegment == "manhua"

        var (captured, completed) = capturePages(chapterUrl, urlSegment, isWebtoon)
        // A captcha never clears itself, so it is handled before any retry budget is spent. Each
        // round solves the page the WebView saw; a failed solve reloads the chapter, which serves a
        // fresh set of strips, and a fresh captcha is what the heuristic is right about ~97% of the
        // time. Repeating on new captchas is stronger than insisting on the same one.
        var captchaRounds = 0
        while (captured.isEmpty() && readerJsInterface.captchaSeen && captchaRounds < CAPTCHA_REFRESH_ATTEMPTS) {
            captchaRounds++
            val captchaHtml = readerJsInterface.captchaPageHtml
            val solved = captchaHtml != null && solveChallengeFromHtml(captchaHtml, chapterUrl)
            Log.w("JapscanReader", "captcha round $captchaRounds: solved=$solved")
            val retry = capturePages(chapterUrl, urlSegment, isWebtoon)
            captured = retry.first
            completed = retry.second
        }
        if (captured.isEmpty() && readerJsInterface.cloudflareSeen) {
            // A Cloudflare challenge cannot be answered by a detached WebView: hand it to the same
            // visible window the app would use, then try again.
            Log.w("JapscanReader", "Cloudflare challenge in the WebView, asking the user to clear it")
            askUserForWebViewCaptcha(chapterUrl, isReader)
            val afterChallenge = capturePages(chapterUrl, urlSegment, isWebtoon)
            captured = afterChallenge.first
            completed = afterChallenge.second
        }
        if (captured.isEmpty() && readerJsInterface.captchaSeen && System.currentTimeMillis() < captchaBlockedUntil) {
            // A rate limit is not a captcha nobody can solve: the site simply refuses validations
            // for a minute. Waiting is the transparent answer, and it is also the only one that
            // does not spend more of the same budget.
            val wait = captchaBlockedUntil - System.currentTimeMillis()
            Log.w("JapscanReader", "captcha rate-limited, waiting ${wait / 1000}s before asking the user")
            delay(wait)
            val afterWait = capturePages(chapterUrl, urlSegment, isWebtoon)
            captured = afterWait.first
            completed = afterWait.second
            val freshCaptcha = readerJsInterface.captchaPageHtml
            if (captured.isEmpty() && freshCaptcha != null && solveChallengeFromHtml(freshCaptcha, chapterUrl)) {
                val afterSolve = capturePages(chapterUrl, urlSegment, isWebtoon)
                captured = afterSolve.first
                completed = afterSolve.second
            }
        }
        if (captured.isEmpty() && readerJsInterface.captchaSeen) {
            // Only a visible WebView can clear what is left.
            Log.w("JapscanReader", "captcha still up after $captchaRounds rounds, asking the user to solve it")
            askUserForWebViewCaptcha(chapterUrl, isReader)
            val afterUser = capturePages(chapterUrl, urlSegment, isWebtoon)
            captured = afterUser.first
            completed = afterUser.second
        }
        // The site intermittently serves the reader with an empty page list: the HTML is the real
        // chapter page, but it carries no pages and no third-party script. Nothing on our side
        // provokes it, and a later attempt gets a good page, so retry with a growing pause instead
        // of treating it as an empty chapter.
        for ((attempt, pause) in CAPTURE_BACKOFF.withIndex()) {
            if (captured.isNotEmpty()) break
            Log.w(
                "JapscanReader",
                "empty capture with webtoon=$isWebtoon, retry ${attempt + 1}/${CAPTURE_BACKOFF.size} in ${pause.inWholeSeconds}s",
            )
            delay(pause)
            val retry = capturePages(chapterUrl, urlSegment, isWebtoon)
            captured = retry.first
            completed = retry.second
        }
        if (captured.isEmpty()) {
            Log.w(
                "JapscanReader",
                "still no page with webtoon=$isWebtoon, retrying with webtoon=${!isWebtoon}",
            )
            isWebtoon = !isWebtoon
            val otherMode = capturePages(chapterUrl, urlSegment, isWebtoon)
            captured = otherMode.first
            completed = otherMode.second
        }

        Log.d("JapscanReader", "getPageList end: captured=${captured.size} webtoon=$isWebtoon url=$chapterUrl")
        // A partial capture must never be cached: serving 168 pages for a 195-page chapter for the
        // next 15 minutes is worse than capturing it again.
        if (captured.isNotEmpty() && completed) {
            rememberPageList(chapterUrl, captured)
        } else if (captured.isNotEmpty()) {
            Log.w(
                "JapscanReader",
                "partial capture (${captured.size} pages, done=false), not cached: $chapterUrl",
            )
        }
        if (captured.isEmpty()) {
            Log.w("JapscanReader", "getPageList finished with zero page: $chapterUrl (captcha=${readerJsInterface.captchaSeen})")
            // A captcha the solver could not answer looks nothing like an empty chapter, and saying
            // so is the difference between "retry" and "this chapter is gone".
            redirectedTo?.let { finalUrl ->
                throw Exception("Ce chapitre n'existe plus sur Japscan : il redirige vers $finalUrl")
            }
            throw if (readerJsInterface.captchaSeen) {
                Exception("Le captcha de Japscan n'a pas pu être résolu. Réessaie : la tentative suivante part souvent d'une nouvelle série d'images.")
            } else {
                Exception("Aucune page trouvée pour ce chapitre (chapitre vide ou supprimé sur Japscan)")
            }
        }
        return captured.mapIndexed { i, path -> Page(i, imageUrl = "https://$CACHE_HOST$path") }
    }

    private fun openCaptchaWebView(chapterUrl: String) {
        val context = applicationContext
        try {
            val intent = Intent().apply {
                component = ComponentName(context, "eu.kanade.tachiyomi.ui.webview.WebViewActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("url_key", chapterUrl)
                putExtra("source_key", id)
                putExtra("title_key", "Résolvez le captcha, fermez la Webview et réouvrez le chapitre.")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Suwayomi etc.
            throw Exception("Résolvez le captcha de ce chapitre depuis la WebView et réouvrez le chapitre.")
        }
    }

    /**
     * Last resort: the site captcha that only a real WebView sees survived every automatic attempt,
     * so open the visible WebView and give the user a moment before retrying.
     */
    private suspend fun askUserForWebViewCaptcha(chapterUrl: String, isReader: Boolean) {
        warmupWebViewSession()
        openCaptchaWebView(chapterUrl)
        delay(CAPTCHA_WEBVIEW_WAIT)
        // The clearance the user earned has to reach the cookie store the capture WebView reads
        // from, or the next attempt starts on the challenge again.
        runCatching { CookieManager.getInstance().flush() }
        // Leaving the WebView on screen once the challenge is cleared is just in the way.
        closeVisibleWebView(isReader)
        // Cloudflare commits its cookie just after the page settles; retrying instantly is what
        // made a freshly cleared challenge look like an empty chapter.
        delay(2.seconds)
    }

    private fun closeVisibleWebView(isReader: Boolean) {
        val context = applicationContext
        runCatching {
            val target = if (isReader) {
                "eu.kanade.tachiyomi.ui.reader.ReaderActivity"
            } else {
                "eu.kanade.tachiyomi.ui.main.MainActivity"
            }
            context.startActivity(
                Intent().apply {
                    component = ComponentName(context, target)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
        }.onFailure { Log.d("JapscanReader", "could not close the visible WebView: ${it.message}") }
    }

    /**
     * Solves the strip-ordering captcha from any page HTML: the copy the HTTP client got, or the one
     * the WebView fetched for itself when the captcha is only shown to a real browser.
     */
    private fun solveChallengeFromHtml(pageHtml: String, chapterUrl: String): Boolean {
        val stripsContent = STRIPS_ARRAY_REGEX.find(pageHtml)?.groupValues?.get(1).orEmpty()
        val token = TOKEN_REGEX.find(pageHtml)?.groupValues?.get(1) ?: return false
        if (stripsContent.isEmpty()) return false

        val items = ELEMENT_REGEX.findAll(stripsContent).map {
            it.groupValues[1] to it.groupValues[2]
        }.toList()
        if (items.size != 4) {
            Log.d("JapscanDebug", "Captcha items count is not 4: ${items.size}")
            return false
        }
        val answers = runCatching { orderUuidsByImageVerticalityRanked(items, CAPTCHA_ATTEMPTS) }
            .getOrDefault(emptyList())
        if (answers.isEmpty()) return false
        // The site counts every validation, right or wrong, and answers `too_many_requests` once a
        // budget is spent. Hammering it is what kept a solvable captcha failing: the extension then
        // jumped straight to the visible WebView, and the whole thing looked broken.
        if (System.currentTimeMillis() < captchaBlockedUntil) {
            Log.d("JapscanDebug", "Captcha rate-limited, ${(captchaBlockedUntil - System.currentTimeMillis()) / 1000}s left")
            return false
        }
        // The scoring is a heuristic and the site accepts several answers per token: submit the
        // best candidate, then the next best ones, instead of stopping at the first miss.
        for (answer in answers) {
            Log.d("JapscanDebug", "Captcha ordered UUIDs: $answer")
            val accepted = runCatching {
                val multipartBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("token", token)
                    .addFormDataPart("answer", answer.toJsonString())
                    .build()

                val captchaRequest = Request.Builder()
                    .url("$baseUrl/validate-captcha/")
                    .header("Referer", chapterUrl)
                    .post(multipartBody)
                    .build()

                client.newCall(captchaRequest).execute().use { resp ->
                    val body = resp.body.string()
                    Log.d("JapscanDebug", "Captcha response: $body")
                    if (CAPTCHA_RATE_LIMIT_REGEX.containsMatchIn(body)) {
                        captchaBlockedUntil = System.currentTimeMillis() + CAPTCHA_COOLDOWN.inWholeMilliseconds
                        Log.w("JapscanReader", "captcha rate-limited, pausing ${CAPTCHA_COOLDOWN.inWholeSeconds}s")
                    }
                    body.contains(CAPTCHA_SUCCESS_REGEX)
                }
            }.getOrDefault(false)
            if (accepted) return true
            // A rate limit is not a wrong answer: another candidate, or another puzzle, only spends
            // more of the same budget.
            if (System.currentTimeMillis() < captchaBlockedUntil) return false
        }
        return false
    }

    private suspend fun warmupWebViewSession() {
        runCatchingCancellable {
            runWebView<Unit>(timeout = 8.seconds) {
                var finished = false
                onPageFinished { finished = true }
                // Settle window that lets Cloudflare's beacon commit cf_clearance before teardown
                poll(200.milliseconds) { if (finished) resolve(Unit) }
                loadUrl("$baseUrl/")
            }
        }
    }

    /**
     * Loads [chapterUrl] in a WebView with the hooks of one reader mode and collects the pages.
     *
     * @param[chapterUrl] Chapter URL to load.
     * @param[urlSegment] First path segment (`manga`, `manhwa`, `manhua`, `bd`...), used by the JS.
     * @param[isWebtoon] `true` to install the long-strip hooks, `false` for the paginated ones.
     * @return Saved page paths, empty when the reader produced nothing.
     */
    private suspend fun capturePages(chapterUrl: String, urlSegment: String, isWebtoon: Boolean): Pair<List<String>, Boolean> {
        // Pages travel over a loopback socket when it can be started; the bridge stays the
        // fallback for every page the socket does not take.
        val receiver = runCatching { PageReceiver(applicationContext.cacheDir) }
            .onFailure { Log.w("JapscanReader", "page receiver unavailable: ${it.message}") }
            .getOrNull()
        val jsInterface = readerJsInterface
        jsInterface.begin(receiver)
        // Without this the socket capture looks idle to the watchdog and gets cut short.
        receiver?.onPage = { jsInterface.activity.trySend(Unit) }
        Log.d("JapscanReader", "capturePages: webtoon=$isWebtoon url=$chapterUrl")

        // The WebView is deliberately left to obtain and keep its own Cloudflare clearance. The
        // probe above already spent the OkHttp one on a different TLS fingerprint, and Cloudflare
        // burns a clearance it sees used by two clients: the first chapter worked, every later one
        // came back as a reader with no pages.
        val webView = withContext(Dispatchers.Main) {
            val request = CaptureRequest(
                chapterUrl = chapterUrl,
                urlSegment = urlSegment,
                isWebtoon = isWebtoon,
                interfaceName = readerInterfaceName ?: randomString().also { readerInterfaceName = it },
                uploadUrl = receiver?.baseUrl.orEmpty(),
            )
            captureRequest = request
            redirectedTo = null
            seenRequests.clear()
            idleHandler.removeCallbacksAndMessages(null)
            val view = readerWebView ?: createReaderWebView().also { readerWebView = it }
            // Priming loads the homepage first, and the page-finished callback then navigates to
            // the chapter. Skipping the hop has to skip the homepage load too, otherwise the
            // driver runs on the homepage instead of the chapter.
            // Straight to the chapter: the homepage hop it used to take was never needed, the
            // failures it "fixed" were the site captcha, handled on its own now.
            view.loadUrl(chapterUrl, headers.toMap())
            view
        }
        try {
            // Progress-based budget: a 160-page chapter legitimately takes minutes, so the run is
            // only stopped after IDLE_TIMEOUT without a new page. A generous hard cap still guards
            // against a reader that keeps emitting forever.
            val hardDeadline = TimeSource.Monotonic.markNow() + 20.minutes
            while (!jsInterface.done.isCompleted && hardDeadline.hasNotPassedNow()) {
                withTimeoutOrNull(IDLE_TIMEOUT) { jsInterface.activity.receive() } ?: break
            }
        } finally {
            // Japscan sets window.__poisoned when it spots a patched built-in. Log it before the
            // WebView goes away: a poisoned page explains a silent zero-page capture.
            //
            // Every WebView method must run on the thread that created it. Missing the Main
            // switch here threw a RuntimeException out of this `finally`, which discarded a
            // perfectly captured chapter and failed the whole getPageList.
            val probe = withTimeoutOrNull(2.seconds) {
                runCatchingCancellable {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine<String?> { continuation ->
                            webView.evaluateJavascript(
                                "JSON.stringify({poisoned:!!window.__poisoned," +
                                    "imgs:document.images.length," +
                                    "data:Array.prototype.filter.call(document.images,function(i){" +
                                    "return (i.currentSrc||i.src||'').indexOf('data:image/')===0;}).length," +
                                    "blob:Array.prototype.filter.call(document.images,function(i){" +
                                    "return (i.currentSrc||i.src||'').indexOf('blob:')===0;}).length})",
                            ) { value -> continuation.resume(value) }
                        }
                    }
                }.onFailure { Log.w("JapscanReader", "page probe failed: ${it.message}") }.getOrNull()
            }
            Log.w("JapscanReader", "page probe: $probe (webtoon=$isWebtoon)")
            // The WebView is kept for the next chapter: destroying it is what made every other
            // capture come back as an empty shell. It is released once nothing asks for it.
            val finished = captureRequest
            withContext(NonCancellable + Dispatchers.Main) {
                idleHandler.removeCallbacksAndMessages(null)
                idleHandler.postDelayed(
                    {
                        if (captureRequest === finished) {
                            runCatching { readerWebView?.destroy() }
                            readerWebView = null
                            captureRequest = null
                            Log.d("JapscanReader", "reader WebView released after inactivity")
                        }
                    },
                    WEBVIEW_IDLE_MS,
                )
            }
            receiver?.close()
        }

        // A redirect is treated as an empty capture, not as a verdict: the site also redirects
        // transiently (rate limiting, challenge), and one more attempt usually lands on the real
        // page. getPageList only reports the chapter as gone once every attempt hit it.
        redirectedTo?.let { finalUrl -> Log.w("JapscanReader", "capture redirected to $finalUrl, treating it as empty") }
        val captured = receiver?.snapshot().orEmpty() + jsInterface.snapshot()
        val completed = jsInterface.done.isCompleted
        Log.d(
            "JapscanReader",
            "capturePages end: captured=${captured.size} done=$completed webtoon=$isWebtoon",
        )
        return captured to completed
    }

    // A manual WebView instead of runWebView: the long-strip reader needs a forced desktop-sized
    // viewport, otherwise it only renders one tile per page.
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createReaderWebView(): WebView = WebView(applicationContext).apply {
        settings.domStorageEnabled = true
        settings.javaScriptEnabled = true
        settings.blockNetworkImage = false
        // Keep the UA matched to the rest of the traffic so Cloudflare doesn't challenge again;
        // the desktop appearance the descrambler needs is faked at the JS layer.
        settings.userAgentString = headers["User-Agent"]
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        // A WebView that is never attached to a window gets a waived renderer, and the reader's
        // bootstrap is throttled hard enough that it never mounts within the capture window.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        if (captureRequest?.isWebtoon == true) {
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            measure(
                View.MeasureSpec.makeMeasureSpec(WEBVIEW_VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(WEBVIEW_VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WEBVIEW_VIEWPORT_WIDTH, WEBVIEW_VIEWPORT_HEIGHT)
        }
        addJavascriptInterface(readerJsInterface, readerInterfaceName!!)

        // The renderer's own explanation of a failed request (CSP, mixed content, ...) is the only
        // way to tell those apart from a socket that is simply not listening.
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message()
                if (text.contains("Mixed Content", true) || text.contains("Refused to", true) ||
                    text.contains("127.0.0.1", true) || text.contains("localhost", true)
                ) {
                    Log.w("JapscanReader", "console: $text (${message.sourceId()}:${message.lineNumber()})")
                }
                return true
            }
        }

        webViewClient = object : WebViewClient() {
            // The reader pulls rotating ad hosts whose modals break the detached descrambler,
            // so only the origins the reader needs are allowed. Every blocked host is logged once:
            // an over-eager allowlist is invisible otherwise, and it looks exactly like a chapter
            // whose reader never mounts.
            val blockedHosts = Collections.synchronizedSet(mutableSetOf<String>())

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                val host = request.url.host ?: return null
                // The page receiver lives on loopback; blocking it would break our own uploads.
                if (host == LOOPBACK_HOST) return null
                if (ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    val path = request.url.encodedPath ?: ""
                    if (!request.isForMainFrame && path.isNotEmpty() && !path.startsWith("/uploads/") &&
                        seenRequests.size < 40 && seenRequests.add(path)
                    ) {
                        Log.d("JapscanReader", "req: $path")
                    }
                }
                return if (ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    null
                } else {
                    if (blockedHosts.add(host)) {
                        Log.d("JapscanReader", "blocked host: $host${request.url.path}")
                    }
                    // A null body keeps some WebView builds pending, stalling the capture
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val request = captureRequest ?: return
                view?.evaluateJavascript(ACLIB_STUB, null)
                view?.evaluateJavascript(
                    if (request.isWebtoon) webtoonHooks(request.interfaceName) else paginatedHook(request.interfaceName),
                    null,
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Keep the detached WebView's JS timers running
                view?.onResume()
                view?.resumeTimers()
                val request = captureRequest ?: return
                // The browser is the only thing that follows the site's redirects now, so this is
                // where a dead chapter (whose URL lands on the manga page) is detected.
                if (url != null && !url.trimEnd('/').equals(request.chapterUrl.trimEnd('/'), ignoreCase = true)) {
                    redirectedTo = url
                }
                val driver = if (request.isWebtoon) {
                    webtoonDriver(request.interfaceName, request.urlSegment)
                } else {
                    paginatedDriver(request.interfaceName, request.uploadUrl)
                }
                view?.evaluateJavascript(driver, null)
            }
        }

        // No load here: the caller starts the warmup navigation so it can rearm the state first.
    }

    // Spooled pages must outlive the chapter being read (the reader may re-request them or
    // preload the next chapter), so only files older than a day are reaped.

    /** Short-lived page list of a chapter, so a read followed by a download captures it once. */
    private data class CachedChapterPages(val paths: List<String>, val savedAt: Long)

    private val pageListCache = object : LinkedHashMap<String, CachedChapterPages>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedChapterPages>): Boolean = size > PAGE_LIST_CACHE_SIZE
    }

    /** Returns the cached page files for [chapterUrl], dropping the entry when stale or gone. */
    private fun cachedPageList(chapterUrl: String): List<String>? = synchronized(pageListCache) {
        val entry = pageListCache[chapterUrl] ?: return null
        val expired = System.currentTimeMillis() - entry.savedAt > PAGE_LIST_CACHE_TTL_MS
        val missing = entry.paths.any { !File(it).exists() }
        if (expired || missing) {
            pageListCache.remove(chapterUrl)
            return null
        }
        entry.paths
    }

    /** Stores [paths] for [chapterUrl], evicting the least recently used chapter. */
    private fun rememberPageList(chapterUrl: String, paths: List<String>) = synchronized(pageListCache) {
        pageListCache[chapterUrl] = CachedChapterPages(paths, System.currentTimeMillis())
        Log.d("JapscanReader", "page list cached for $chapterUrl (${paths.size} pages)")
    }

    // The sweep only reclaims day-old spool files, so running it on every chapter open is pure
    // directory churn.
    @Volatile
    private var lastSweepAt = 0L

    private fun sweepPageCache(cacheDir: File) {
        val now = System.currentTimeMillis()
        if (now - lastSweepAt < PAGE_CACHE_SWEEP_INTERVAL_MS) return
        lastSweepAt = now
        val cutoff = now - 24.hours.inWholeMilliseconds
        cacheDir.listFiles()?.forEach {
            if (it.name.startsWith(CACHE_FILE_PREFIX) && it.lastModified() < cutoff) it.delete()
        }
    }

    /**
     * Receives pages over a loopback socket instead of the JS bridge.
     *
     * The bridge only transports `String`, so a page has to be base64 encoded on the JS side and
     * copied into a Java string: ~21 ms and ~750 KB of allocations per page, of which only 3 ms is
     * actual decoding and writing. Posting the Blob straight to a local socket ships the same bytes
     * in one copy.
     *
     * The port is ephemeral and the URL carries the page index, so page order does not depend on
     * call order. Every failure is non-fatal: the driver falls back to the bridge.
     */
    private class PageReceiver(private val cacheDir: File) : Closeable {
        // A random suffix: two downloads started in the same millisecond would otherwise write
        // the same files and overwrite each other's pages.
        private val sessionTag = "$CACHE_FILE_PREFIX${System.currentTimeMillis()}-${UUID.randomUUID()}"

        // The reader fires its uploads back to back, several of them before the previous reply
        // lands: a small backlog drops connections and the pages are lost, so accept generously
        // and serve each connection on its own thread.
        private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        private val received = mutableListOf<File>()

        @Volatile
        private var running = true

        /** Called after every received page: the socket path must still feed the idle watchdog. */
        @Volatile
        var onPage: (() -> Unit)? = null

        val baseUrl: String = "http://127.0.0.1:${server.localPort}"

        private val pool = java.util.concurrent.Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "japscan-page").apply { isDaemon = true }
        }

        private val worker = Thread(
            {
                while (running) {
                    try {
                        val socket = server.accept()
                        pool.execute { socket.use { readPage(it) } }
                    } catch (e: Exception) {
                        if (running) Log.w("JapscanReader", "receiver: ${e.message}")
                    }
                }
            },
            "japscan-pages",
        ).apply {
            isDaemon = true
            start()
        }

        private fun readPage(socket: Socket) {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream().buffered()
            val requestLine = readLine(input) ?: return
            var contentLength = -1
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(':').trim().toIntOrNull() ?: -1
                }
            }
            // A Blob body is not a "simple" request, so the browser sends a CORS preflight
            // first; answering it here is what lets the upload through.
            if (requestLine.substringBefore(' ').equals("OPTIONS", ignoreCase = true)) {
                reply(socket, 204)
                return
            }
            val index = requestLine.split(' ').getOrNull(1)?.substringAfterLast('/')?.toIntOrNull()
            if (index == null || contentLength <= 0) {
                reply(socket, 400)
                return
            }
            val file = File(cacheDir, "$sessionTag-$index.bin")
            FileOutputStream(file).use { out -> copyExactly(input, out, contentLength) }
            synchronized(received) { received.add(file) }
            onPage?.invoke()
            reply(socket, 204)
        }

        // One buffer per connection thread: the receiver serves several sockets at once, and a
        // shared buffer would interleave their bytes into each other's pages.
        private val copyBuffer = ThreadLocal.withInitial { ByteArray(64 * 1024) }

        private fun copyExactly(input: InputStream, out: OutputStream, length: Int) {
            val buffer = copyBuffer.get()

            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) return
                out.write(buffer, 0, read)
                remaining -= read
            }
        }

        private fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream(256)
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (line.size() == 0) null else line.toString("ISO-8859-1")
                if (byte == '\n'.code) return line.toString("ISO-8859-1").removeSuffix("\r")
                line.write(byte)
                if (line.size() > MAX_HEADER_LINE) return null
            }
        }

        private fun reply(socket: Socket, code: Int) {
            runCatching {
                val reason = if (code == 204) "No Content" else "Bad Request"
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 $code $reason\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Access-Control-Allow-Methods: POST, OPTIONS\r\n" +
                                "Access-Control-Allow-Headers: *\r\n" +
                                // Without this the browser re-asks before every page, turning each
                                // upload into two round trips.
                                "Access-Control-Max-Age: 86400\r\n" +
                                "Content-Length: 0\r\n" +
                                "Connection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    flush()
                }
            }
        }

        /** Drops every page received so far, so a retry starts from a clean slate. */
        fun clear() {
            synchronized(received) {
                received.forEach { runCatching { it.delete() } }
                received.clear()
            }
        }

        /** Received files in page order, with an exact repeat of the previous page dropped. */
        fun snapshot(): List<String> {
            val ordered = synchronized(received) {
                received.sortedBy { it.name.substringAfterLast('-').substringBefore('.').toIntOrNull() ?: 0 }
            }
            val paths = ArrayList<String>(ordered.size)
            var previous: File? = null
            for (file in ordered) {
                val prev = previous
                if (prev != null && file.length() == prev.length() &&
                    file.readBytes().contentEquals(prev.readBytes())
                ) {
                    Log.d("JapscanReader", "receiver: dropped ${file.name}, duplicate of ${prev.name}")
                    file.delete()
                    continue
                }
                previous = file
                paths.add(file.absolutePath)
            }
            return paths
        }

        override fun close() {
            running = false
            runCatching { server.close() }
            pool.shutdownNow()
        }

        private companion object {
            private const val SOCKET_TIMEOUT_MS = 10_000
            private const val MAX_HEADER_LINE = 8 * 1024
        }
    }

    private fun randomString(length: Int = 10): String = buildString(length) {
        repeat(length) { append(INTERFACE_NAME_CHARS.random()) }
    }

    private class JsInterface(private val cacheDir: File) {
        // Recreated per capture: a CompletableDeferred completes once, and the WebView that owns
        // this interface is now reused across chapters.
        @Volatile
        var done = CompletableDeferred<Unit>()

        val activity = Channel<Unit>(Channel.CONFLATED)

        @Volatile
        private var receiver: PageReceiver? = null

        /** Set when the WebView landed on the site's own captcha page during this capture. */
        @Volatile
        var captchaSeen = false

        /** Set when the WebView was stopped by a Cloudflare challenge instead. */
        @Volatile
        var cloudflareSeen = false

        /** The captcha page as the WebView saw it, so the same solver can run on it. */
        @Volatile
        var captchaPageHtml: String? = null

        private val savedPaths: MutableList<String> = Collections.synchronizedList(mutableListOf())

        // Fresh per capture: reusing the previous tag would overwrite pages the reader or the
        // downloader still has open.
        @Volatile
        private var sessionTag = "$CACHE_FILE_PREFIX${System.currentTimeMillis()}-${UUID.randomUUID()}"

        fun snapshot(): List<String> = synchronized(savedPaths) { savedPaths.toList() }

        /** Rearms this interface for a new chapter. */
        fun begin(receiver: PageReceiver?) {
            done = CompletableDeferred()
            captchaSeen = false
            cloudflareSeen = false
            captchaPageHtml = null
            this.receiver = receiver
            sessionTag = "$CACHE_FILE_PREFIX${System.currentTimeMillis()}-${UUID.randomUUID()}"
            synchronized(savedPaths) { savedPaths.clear() }
            // Drop anything the previous chapter's driver left queued.
            while (activity.tryReceive().isSuccess) { }
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun savePage(payload: String) {
            activity.trySend(Unit)
            // The JS side strips the `data:image/…;base64,` header, so the payload is decoded as
            // it arrives: no ~700 KB copy per page on the Java heap. A data URI is still accepted.
            val base64 = if (payload.startsWith("data:")) payload.substring(payload.indexOf(',') + 1) else payload
            if (base64.isEmpty()) {
                Log.d("JapscanReader", "savePage: empty payload")
                return
            }
            runCatching {
                val startedAt = System.nanoTime()
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val decodedAt = System.nanoTime()
                synchronized(savedPaths) {
                    val file = File(cacheDir, "$sessionTag-${savedPaths.size}.bin")
                    file.writeBytes(bytes)
                    val writtenAt = System.nanoTime()
                    savedPaths.add(file.absolutePath)
                    // One line per 25 pages: the driver logs the aggregate at the end.
                    if (savedPaths.size % 25 == 0) {
                        Log.d(
                            "JapscanReader",
                            "savePage: page ${savedPaths.size} (${bytes.size} bytes, " +
                                "decode=${(decodedAt - startedAt) / 1_000_000}ms " +
                                "write=${(writtenAt - decodedAt) / 1_000_000}ms " +
                                "chars=${payload.length})",
                        )
                    }
                }
            }.onFailure { Log.w("JapscanReader", "savePage failed: ${it.message}") }
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun resetPages() {
            synchronized(savedPaths) {
                savedPaths.forEach { runCatching { File(it).delete() } }
                savedPaths.clear()
            }
            receiver?.clear()
            Log.d("JapscanReader", "resetPages: cache cleared")
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun captchaNeeded() {
            captchaSeen = true
            activity.trySend(Unit)
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun cloudflareNeeded() {
            cloudflareSeen = true
            activity.trySend(Unit)
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun captchaPage(html: String) {
            captchaPageHtml = html
            activity.trySend(Unit)
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun keepAlive() {
            activity.trySend(Unit)
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun log(message: String) {
            Log.d("JapscanReader", message)
        }

        @JavascriptInterface
        @Suppress("UNUSED")
        fun passDone() {
            done.complete(Unit)
            activity.trySend(Unit)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = SHOW_SPOILER_CHAPTERS
            title = SHOW_SPOILER_CHAPTERS_TITLE
            entries = arrayOf("Montrer uniquement les chapitres traduit en Français", "Montrer les chapitres spoiler")
            entryValues = arrayOf("hide", "show")
            summary = "%s"
            setDefaultValue("hide")
        }.let(screen::addPreference)
    }

    companion object {
        private val CHAPTER_PATH_TYPES = setOf("manga", "manhua", "manhwa", "bd", "comic")
        private val HIDDEN_STYLE_TOKENS = listOf(
            "display:none",
            "visibility:hidden",
            "visibility:collapse",
            "content-visibility:hidden",
            "pointer-events:none",
            "clip-path:inset(100%",
            "clip-path:circle(0",
            "clip-path:ellipse(0",
            "clip-path:polygon(0,0,0,0",
            "clip:rect(0,0,0,0",
            "font-size:0",
            "line-height:0",
            "text-indent:-",
        )

        // Styles that visually remove an element while leaving it in the DOM: zero opacity/size,
        // 3+ digit off-screen offsets or translations, and collapsed transforms. Zero values are
        // anchored so visible values like `opacity:0.9` or `min-width:0` don't match.
        private val HIDDEN_STYLE_REGEX = Regex(
            """(?:^|;)opacity:0(?![.\d])""" +
                """|filter:opacity\(0(?![.\d])""" +
                """|(?:^|;)(?:width|height):0(?![.\d])""" +
                """|max-(?:width|height):0(?![.\d])""" +
                """|(?:top|bottom|left|right|inset):-?\d{3,}""" +
                """|transform:translate(?:3d|x|y)?\([^)]*-?\d{3,}""" +
                """|transform:scale(?:3d|x|y)?\(0[,)]""" +
                """|transform:matrix\(0,0,0,0""",
        )
        private val CHAPTER_NUM_REGEX = Regex("""(?i)chapitre\s+([\d.]+)""")
        private val NON_NUMBER_REGEX = Regex("[^0-9.]+")
        private val CAPTCHA_SUCCESS_REGEX = Regex(""""success":\s*true""")
        private val CAPTCHA_RATE_LIMIT_REGEX = Regex("""too_many_requests|Trop de tentatives""")

        /** How long the site refuses captcha validations after a burst. */
        private val CAPTCHA_COOLDOWN = 65.seconds
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        private val PARIS = ZoneId.of("Europe/Paris")

        private const val SHOW_SPOILER_CHAPTERS_TITLE = "Les chapitres en Anglais ou non traduit sont upload en tant que \" Spoilers \" sur Japscan"
        private const val SHOW_SPOILER_CHAPTERS = "JAPSCAN_SPOILER_CHAPTERS"
        private val STRIPS_ARRAY_REGEX = """(?:strips|"[a-f0-9]{6}")\s*:\s*(\[[^\]]*\])""".toRegex()
        private val ELEMENT_REGEX = """\{\s*"uuid"\s*:\s*"([^"]+)"\s*,\s*"src"\s*:\s*"([^"]*)"\s*\}""".toRegex()
        private val TOKEN_REGEX = """(?:token|"[a-f0-9]{6}")\s*:\s*"([0-9a-fA-F]{64})"""".toRegex()

        // A const string instead of ('a'..'z') + ('A'..'Z'): a CharRange is an Iterable, not a
        // Collection, so the plus fell back to the iterating overload.
        private const val INTERFACE_NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        private const val CACHE_HOST = "japscan-cache.local"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val CACHE_FILE_PREFIX = "japscan-"

        /** How long a captured page list is reused (read then download). */
        private const val PAGE_LIST_CACHE_TTL_MS = 15 * 60 * 1000L

        /** Maximum number of chapters kept in the page list cache. */
        private const val PAGE_LIST_CACHE_SIZE = 30

        /** Minimum delay between two sweeps of the spool directory. */
        private const val PAGE_CACHE_SWEEP_INTERVAL_MS = 60 * 60 * 1000L
        private val IDLE_TIMEOUT = 10.seconds

        /** Pauses before re-capturing a chapter the site answered with an empty reader. */
        private val CAPTURE_BACKOFF = listOf(5.seconds, 15.seconds, 45.seconds)

        /** How long the shared reader WebView is kept once no capture is using it. */
        private const val WEBVIEW_IDLE_MS = 5 * 60 * 1000L

        /** Candidate orders submitted for one captcha, best first. */
        private const val CAPTCHA_ATTEMPTS = 3

        /** Fresh captchas tried (by reloading the chapter) before handing over to the user. */
        private const val CAPTCHA_REFRESH_ATTEMPTS = 3

        /** Time given to the user to clear a captcha that only the WebView shows. */
        private val CAPTCHA_WEBVIEW_WAIT = 25.seconds

        // Under ~1280px wide the long-strip reader lazy-loads tiles on scroll, which a detached
        // WebView can't trigger; above it every tile host is created upfront.
        private const val WEBVIEW_VIEWPORT_WIDTH = 1920
        private const val WEBVIEW_VIEWPORT_HEIGHT = 16384

        private val ALLOWED_HOSTS = listOf(
            "japscan.foo",
            "cdnjs.cloudflare.com",
            "code.jquery.com",
            "cdn.jsdelivr.net",
            "fonts.googleapis.com",
            "fonts.gstatic.com",
            "challenges.cloudflare.com",
        )
    }
}
