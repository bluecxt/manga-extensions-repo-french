package eu.kanade.tachiyomi.extension.fr.japscan

import android.util.Log
import android.webkit.CookieManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Shares cookies between the app's WebView and the extension's HTTP client.
 *
 * Japscan ties both its captcha clearance and its Cloudflare clearance to the browser session: the
 * reader WebView earns them, and an HTTP request that does not carry the same `Cookie` header is
 * answered with a fresh captcha on every try. The client handed to extensions has no cookie jar of
 * its own, so requests are decorated from the shared [CookieManager] here and the answers are
 * written back, which is what lets the HTTP capture reuse a session the WebView already cleared.
 */
internal object CookieSync {
    fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val manager = runCatching { CookieManager.getInstance() }.getOrNull()
            ?: return chain.proceed(request)

        val cookie = runCatching { manager.getCookie(request.url.toString()) }.getOrNull()
        // A jar-provided Cookie header wins: merging two sources would duplicate attributes.
        val outgoing = if (cookie.isNullOrEmpty() || request.header("Cookie") != null) {
            request
        } else {
            request.newBuilder().header("Cookie", cookie).build()
        }

        val response = chain.proceed(outgoing)
        runCatching {
            val setCookies = response.headers.values("Set-Cookie")
            if (setCookies.isNotEmpty()) {
                val url = request.url.toString()
                setCookies.forEach { manager.setCookie(url, it) }
                manager.flush()
                if (DEBUG) Log.d("JapscanReader", "http: stored ${setCookies.size} cookie(s) for ${request.url.host}")
            }
        }
        return response
    }

    private const val DEBUG = true
}
