package eu.kanade.tachiyomi.extension.fr.japscan

import android.util.Log
import keiyoushi.network.Fetch
import keiyoushi.network.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/**
 * Reads a chapter over HTTP instead of driving the site's reader in a WebView.
 *
 * Japscan publishes everything needed in the HTML it serves: the ciphertext, its parameters and,
 * on the CDN, plain JPEGs. One request for the markup and one per image, all in parallel, replace
 * a navigation, a script render and a blob upload per page.
 *
 * @param client The source client, so Cloudflare clearance and cookies are shared with the app.
 * @param headers Headers of the source, including the user agent the clearance was issued for.
 * @param baseUrl Site root, used for the image `Referer`.
 * @param cacheDir Directory the pages are spooled to; the sentinel host of the source serves them.
 * @param cdnUrl Base URL of the image CDN.
 */
internal class HttpCapture(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val baseUrl: String,
    private val cacheDir: File,
    private val cdnUrl: String,
    /** Number of images in flight at once. */
    var concurrency: Int = DEFAULT_CONCURRENCY,
    /** Directory a rejected markup is dumped to, for diagnosing the gate. */
    private val debugDir: File? = null,
) {
    /**
     * Outcome of [spool].
     *
     * @property paths Absolute paths of the spooled pages, in page order.
     * @property requested Pages the chapter announced.
     * @property elapsedMs Wall time of the whole download.
     */
    data class Spool(val paths: List<String>, val requested: Int, val elapsedMs: Long) {
        val complete: Boolean get() = paths.size == requested
    }

    /**
     * Fetches the chapter markup, clearing the captcha when the site asks for one.
     *
     * @param solveCaptcha Called with the served HTML; returns `true` once the site accepted an
     * answer, which is then followed by a fresh GET.
     * @return The markup of the real chapter page, or `null` when it could not be obtained.
     */
    suspend fun chapterHtml(chapterUrl: String, solveCaptcha: (String) -> Boolean): String? {
        var html = text(chapterUrl) ?: return null
        var rounds = 0
        // The gate is decided by the markup itself, not by the captcha markers: the real chapter
        // page ships the captcha bundle too, with `window.__captcha = { needed: false }`.
        while (!hasPage(html) && rounds < CAPTCHA_ROUNDS) {
            rounds++
            dump(html, "captcha-$rounds")
            val solved = solveCaptcha(html)
            Log.d("JapscanReader", "http: gate round $rounds solved=$solved")
            if (!solved) return null
            html = text(chapterUrl) ?: return null
        }
        return html.takeIf { hasPage(it) }
    }

    /** Reports whether [html] carries a chapter payload, which a gated page never does. */
    fun hasPage(html: String): Boolean = JapscanCrypto.config(html)?.payload?.isNotEmpty() == true

    /**
     * Downloads every page of a chapter and spools it to [cacheDir].
     *
     * @param paths CDN paths as decrypted from the payload.
     * @return The spooled files in page order; an image that failed is absent, which lets the
     * caller decide whether the chapter is complete enough to serve.
     */
    suspend fun spool(paths: List<String>): Spool = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (paths.isEmpty()) return@withContext Spool(emptyList(), 0, 0)
        // Cloudflare sometimes serves a 403 from its cache for a path the origin would accept; the
        // site's own reader dodges it with a per-request query parameter.
        val cacheBuster = Integer.toHexString(CACHE_BUSTER.nextInt(0x10000))
        val tag = "japscan-$started-${UUID.randomUUID().toString().take(8)}"
        val files = arrayOfNulls<File>(paths.size)

        // The CDN is behind Cloudflare and only accepts what a browser session earned: one probe
        // says whether this chapter can be read over HTTP at all. When it is refused, insisting
        // only feeds the bot score that blocked us, so the chapter goes to the WebView instead of
        // burning a download pass that cannot succeed.
        val probeFile = File(cacheDir, "$tag-0.bin")
        if (download(url(paths[0], cacheBuster), probeFile) != 200) {
            Log.d("JapscanReader", "http: CDN refused the probe, the chapter goes to the WebView")
            return@withContext Spool(emptyList(), paths.size, System.currentTimeMillis() - started)
        }

        val gate = Semaphore(concurrency)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        coroutineScope {
            paths.mapIndexed { index, path ->
                async {
                    gate.withPermit {
                        val file = File(cacheDir, "$tag-$index.bin")
                        val code = download(url(path, cacheBuster), file)
                        if (code == 200) files[index] = file else failures.incrementAndGet()
                    }
                }
            }.awaitAll()
        }
        // A path can also answer 404 once and 200 on the next: the CDN caches a miss for a short
        // while, and one stale entry is not worth the WebView fallback. Retry the misses in
        // parallel with a different cache buster.
        val retryBuster = Integer.toHexString(CACHE_BUSTER.nextInt(0x10000))
        coroutineScope {
            paths.indices.filter { files[it] == null }.map { index ->
                async {
                    gate.withPermit {
                        val file = File(cacheDir, "$tag-$index.bin")
                        if (download(url(paths[index], retryBuster), file) == 200) {
                            files[index] = file
                        } else {
                            Log.d("JapscanReader", "http: image still missing: …${paths[index].takeLast(28)}")
                        }
                    }
                }
            }.awaitAll()
        }
        val spooled = files.filterNotNull()
        Spool(spooled.map { it.absolutePath }, paths.size, System.currentTimeMillis() - started)
            .also {
                Log.d(
                    "JapscanReader",
                    "http: download ${it.paths.size}/${it.requested} in ${it.elapsedMs}ms " +
                        "(${failures.get()} refused)",
                )
            }
    }

    /** Writes [html] next to the app's external files so it can be pulled with adb. */
    private fun dump(html: String, name: String) {
        val dir = debugDir ?: return
        runCatching {
            dir.mkdirs()
            File(dir, "japscan-http-$name.html").writeText(html)
            Log.d("JapscanReader", "http: dumped ${html.length} chars to ${File(dir, "japscan-http-$name.html")}")
        }
    }

    private suspend fun text(url: String): String? = runCatchingCancellable {
        client.get(url, headers, Fetch.NONE, ensureSuccess = false).use { response ->
            Log.d("JapscanReader", "http: GET ${response.code} ${response.body.contentLength()}B ${response.request.url}")
            if (response.isSuccessful) response.body.string() else null
        }
    }.onFailure { Log.w("JapscanReader", "http: GET $url failed: ${it.message}") }.getOrNull()

    private suspend fun download(url: String, target: File): Int = runCatchingCancellable {
        client.get(url, imageHeaders, Fetch.NONE, ensureSuccess = false).use { response ->
            if (!response.isSuccessful) {
                if (response.code == 403 || response.code == 503) {
                    Log.d("JapscanReader", "http: image ${response.code} ${response.body.string().take(120)}")
                } else {
                    Log.d("JapscanReader", "http: image ${response.code} ${response.request.url}")
                }
                return@use response.code
            }
            response.body.byteStream().use { input ->
                target.outputStream().buffered(SPOOL_BUFFER).use { out -> input.copyTo(out, SPOOL_BUFFER) }
            }
            if (target.length() > 0) 200 else -1
        }
    }.getOrDefault(-1)

    // Headers the reader's own images carry. Cloudflare's bot rules on the CDN treat a wildcard
    // Accept differently from a real image request, so the browser's values are reproduced.
    private val imageHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .set("Referer", "$baseUrl/")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-site")
            .build()
    }

    /** Absolute CDN URL of a decrypted path, with the cache-busting parameter of this chapter. */
    fun url(path: String, cacheBuster: String): String {
        val absolute = when {
            path.startsWith("http") -> path
            path.startsWith("/") -> cdnUrl + path
            else -> "$cdnUrl/$path"
        }
        return if (absolute.contains('?')) "$absolute&be=$cacheBuster" else "$absolute?be=$cacheBuster"
    }

    private companion object {
        /** 8 in flight saturates a mobile link without hammering the CDN. */
        const val DEFAULT_CONCURRENCY = 8
        const val CAPTCHA_ROUNDS = 3

        const val SPOOL_BUFFER = 64 * 1024
        val CACHE_BUSTER = java.util.Random()
    }
}
