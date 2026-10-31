package keiyoushi.lib.twocaptcha

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import keiyoushi.utils.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TwoCaptcha(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank()

    /**
     * Solves a Cloudflare Turnstile challenge using 2Captcha TurnstileTaskProxyless.
     * Returns the cf-turnstile-response token, or null if solving failed or timed out.
     */
    fun solveTurnstile(
        websiteUrl: String,
        websiteKey: String,
        action: String = "managed",
        data: String = "",
        pagedata: String = "",
        userAgent: String = "",
        timeoutMs: Long = 60_000L,
    ): String? {
        if (!isConfigured) return null

        val taskObj = mutableMapOf<String, String>()
        taskObj["type"] = "TurnstileTaskProxyless"
        taskObj["websiteURL"] = websiteUrl
        taskObj["websiteKey"] = websiteKey
        if (action.isNotEmpty()) taskObj["action"] = action
        if (data.isNotEmpty()) taskObj["data"] = data
        if (pagedata.isNotEmpty()) taskObj["pagedata"] = pagedata
        if (userAgent.isNotEmpty()) taskObj["userAgent"] = userAgent

        val createBody = buildString {
            append("{\"clientKey\":\"").append(apiKey).append("\",\"task\":{")
            val entries = taskObj.entries.toList()
            for (i in entries.indices) {
                append("\"").append(entries[i].key).append("\":\"").append(entries[i].value.replace("\"", "\\\"")).append("\"")
                if (i < entries.size - 1) append(",")
            }
            append("}}")
        }

        val createReq = Request.Builder()
            .url("https://api.2captcha.com/createTask")
            .post(createBody.toRequestBody("application/json".toMediaType()))
            .build()

        val createResp = client.newCall(createReq).execute()
        val createRespStr = createResp.body.string()
        val createJson = Json.parseToJsonElement(createRespStr).jsonObject
        val taskId = createJson["taskId"]?.let {
            it.toString().trim('"').toLongOrNull()
        } ?: return null

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(3000)
            val resultBody = "{\"clientKey\":\"$apiKey\",\"taskId\":$taskId}"
            val resultReq = Request.Builder()
                .url("https://api.2captcha.com/getTaskResult")
                .post(resultBody.toRequestBody("application/json".toMediaType()))
                .build()

            val resultResp = client.newCall(resultReq).execute()
            val resultStr = resultResp.body.string()
            val resultJson = Json.parseToJsonElement(resultStr).jsonObject
            val status = resultJson["status"]?.let { it.toString().trim('"') }
            if (status == "ready") {
                val sol = resultJson["solution"]?.jsonObject
                return sol?.get("token")?.let { it.toString().trim('"') }
            }
        }
        return null
    }

    companion object {
        const val PREF_KEY_2CAPTCHA = "twocaptcha_api_key"

        /**
         * Helper to create and add the 2Captcha EditTextPreference to a PreferenceScreen.
         */
        fun addPreferenceToScreen(
            screen: PreferenceScreen,
            currentKey: String,
            title: String = "Clé API 2Captcha",
            dialogTitle: String = "Entrez votre clé API 2Captcha",
            key: String = PREF_KEY_2CAPTCHA,
        ): EditTextPreference = EditTextPreference(screen.context).apply {
            this.key = key
            this.title = title
            this.dialogTitle = dialogTitle
            this.summary = if (currentKey.isNotBlank()) "Clé configurée" else "Non configurée (laisser vide pour résoudre manuellement dans la WebView)"
            setOnPreferenceChangeListener { _, newValue ->
                val str = newValue as? String ?: ""
                summary = if (str.trim().isNotEmpty()) "Clé configurée" else "Non configurée"
                true
            }
            screen.addPreference(this)
        }

        /**
         * Turnstile interception script for Android WebView onPageStarted.
         */
        fun turnstileInterceptorScript(interfaceBridgeName: String): String = """
            (function(){
                if (window.__tsHooked) return;
                window.__tsHooked = true;
                var checkInterval = setInterval(function() {
                    if (window.turnstile && window.turnstile.render) {
                        clearInterval(checkInterval);
                        var origRender = window.turnstile.render;
                        window.turnstile.render = function(container, params) {
                            if (params && params.sitekey) {
                                window.__tsCallback = params.callback;
                                var payload = JSON.stringify({
                                    sitekey: params.sitekey,
                                    url: window.location.href,
                                    action: params.action || 'managed',
                                    data: params.cData || '',
                                    pagedata: params.chlPageData || '',
                                    userAgent: navigator.userAgent
                                });
                                try {
                                    window.$interfaceBridgeName.onTurnstileDetected(payload);
                                } catch(e) {}
                            }
                            return origRender.apply(this, arguments);
                        };
                    }
                }, 10);
            })();
        """.trimIndent()

        /**
         * OkHttp Interceptor that detects Cloudflare Turnstile challenges (HTTP 403/503),
         * solves them via 2Captcha using a headless background WebView, saves the cf_clearance cookie,
         * and automatically retries the request.
         */
        fun createCloudflareInterceptor(
            apiKeyProvider: () -> String,
            timeoutMs: Long = 60_000L,
        ): okhttp3.Interceptor = okhttp3.Interceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)

            if (response.code in listOf(403, 503)) {
                val isCf = response.header("Server")?.contains("cloudflare", ignoreCase = true) == true ||
                    response.header("cf-ray") != null ||
                    response.peekBody(1024).string().contains("Just a moment", ignoreCase = true)

                if (isCf) {
                    val apiKey = apiKeyProvider().trim()
                    if (apiKey.isEmpty()) {
                        throw java.io.IOException("Cloudflare challenge détecté (HTTP ${response.code}). Clé 2Captcha non configurée. Ouvrez dans la WebView pour résoudre.")
                    }

                    android.util.Log.d("TwoCaptcha", "Cloudflare HTTP ${response.code} detected on ${request.url}. Attempting 2Captcha solve...")
                    response.close()

                    val solved = solveCloudflareInWebView(
                        url = request.url.toString(),
                        apiKey = apiKey,
                        userAgent = request.header("User-Agent").orEmpty(),
                        timeoutMs = timeoutMs,
                    )

                    if (!solved) {
                        throw java.io.IOException("Échec de la résolution automatique 2Captcha. Ouvrez dans la WebView.")
                    }

                    // Retry original request with newly acquired cf_clearance
                    val newRequest = request.newBuilder().build()
                    return@Interceptor chain.proceed(newRequest)
                }
            }
            response
        }

        private fun solveCloudflareInWebView(
            url: String,
            apiKey: String,
            userAgent: String,
            timeoutMs: Long,
        ): Boolean {
            val latch = java.util.concurrent.CountDownLatch(1)
            var success = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            handler.post {
                val context = uy.kohesive.injekt.Injekt.get<android.app.Application>()
                val webView = android.webkit.WebView(context)
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    if (userAgent.isNotEmpty()) userAgentString = userAgent
                }

                val bridge = object {
                    private val tsStarted = java.util.concurrent.atomic.AtomicBoolean(false)

                    @android.webkit.JavascriptInterface
                    fun onTurnstileDetected(payloadJson: String) {
                        if (tsStarted.getAndSet(true)) return
                        Thread {
                            try {
                                val json = Json.parseToJsonElement(payloadJson).jsonObject
                                val solver = TwoCaptcha(apiKey)
                                val token = solver.solveTurnstile(
                                    websiteUrl = json["url"]!!.string,
                                    websiteKey = json["sitekey"]!!.string,
                                    action = json["action"]?.string.orEmpty().ifEmpty { "managed" },
                                    data = json["data"]?.string.orEmpty(),
                                    pagedata = json["pagedata"]?.string.orEmpty(),
                                    userAgent = json["userAgent"]?.string.orEmpty(),
                                )
                                if (!token.isNullOrEmpty()) {
                                    handler.post {
                                        webView.evaluateJavascript("if(window.__tsCallback){window.__tsCallback('$token');}", null)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("TwoCaptcha", "Error solving turnstile in background", e)
                            }
                        }.start()
                    }
                }

                webView.addJavascriptInterface(bridge, "cfBridge")

                webView.webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageStarted(view: android.webkit.WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, pageUrl, favicon)
                        view?.evaluateJavascript(turnstileInterceptorScript("cfBridge"), null)
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        val cookies = cookieManager.getCookie(pageUrl.orEmpty()).orEmpty()
                        if (cookies.contains("cf_clearance")) {
                            success = true
                            latch.countDown()
                            cookieManager.flush()
                        }
                    }
                }

                webView.loadUrl(url)

                // Polling for cf_clearance in case onPageFinished isn't triggered after post-turnstile reload
                val checkRunnable = object : Runnable {
                    override fun run() {
                        if (latch.count == 0L) return
                        val cookies = cookieManager.getCookie(url).orEmpty()
                        if (cookies.contains("cf_clearance")) {
                            success = true
                            latch.countDown()
                            cookieManager.flush()
                            return
                        }
                        handler.postDelayed(this, 1000)
                    }
                }
                handler.postDelayed(checkRunnable, 1000)
            }

            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            return success
        }
    }
}
