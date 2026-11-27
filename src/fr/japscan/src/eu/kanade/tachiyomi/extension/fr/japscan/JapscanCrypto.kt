package eu.kanade.tachiyomi.extension.fr.japscan

import android.util.Base64
import org.json.JSONObject

/**
 * The chapter-page parameters that Japscan regenerates on every single request: the custom element
 * that carries the ciphertext, the two rotating attribute names and the cipher offset.
 *
 * @property tagName Tag of the custom element holding the payload (`d-1aa17`).
 * @property payloadAttribute Suffix of the `data-` attribute holding the ciphertext (`afe733`).
 * @property indicatorAttribute Suffix of the `data-` attribute holding the base64 JSON key
 * (`k2d2a5`).
 * @property offset Characters to drop from the front of the ciphertext before decoding.
 * @property payload The ciphertext itself.
 * @property indicator The base64 JSON key the page list is stored under.
 */
internal data class ReaderPage(
    val tagName: String,
    val payloadAttribute: String,
    val indicatorAttribute: String,
    val offset: Int,
    val payload: String,
    val indicator: String,
)

/**
 * Reads a Japscan chapter page without a browser.
 *
 * The reader decrypts its own page list in JavaScript: it slices the ciphertext, maps every
 * character through a 62-character substitution alphabet, base64-decodes the result and reads the
 * JSON array named by the indicator attribute. Porting those steps to Kotlin is what makes a
 * chapter cost one HTML request plus the images instead of a full WebView render.
 *
 * The only secret is the alphabet, which belongs to the `reader.js` build and is constant for it —
 * see [TableLearning].
 */
internal object JapscanCrypto {
    /** `var <random> = ["eyJkIj...", ...]`: config chunks that concatenate into JSON. */
    private val CONFIG_ARRAY = Regex("""var\s+\w+\s*=\s*\[((?:"[^"]*"\s*,?\s*)+)]""")

    /** The hashed reader bundle, the build that defines the substitution alphabet. */
    private val READER_SCRIPT = Regex("""<script[^>]*src=["']([^"']*[a-f0-9]{32}\.js[^"']*)["']""")

    /**
     * Advertisement slot index. Only `p4c8` marks one: the `_d*` keys of recent payloads are
     * counters (`_d3` currently sits on a real page index, so reading it would drop a page).
     */
    private val AD_INDEX = Regex(""""(?:p4c8)"\s*:\s*(\d+)""")

    /** Quotes, commas and whitespace that separate the config chunks. */
    private val CHUNK_NOISE = Regex("""["',\s]""")

    /** Base64 alphabet order, the target of the substitution. */
    const val STANDARD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** Returns the `reader.js` path referenced by [html], the key the alphabet is stored under. */
    fun readerScript(html: String): String? = READER_SCRIPT.find(html)?.groupValues?.get(1)

    /**
     * Extracts the inline `<script>` that publishes the page config.
     *
     * The whole element is returned, not only its `var` statement: the reader also reads the globals
     * that script sets, and the learning page has to reproduce them.
     */
    fun headScript(html: String): String? {
        val match = CONFIG_ARRAY.find(html) ?: return null
        val start = html.lastIndexOf("<script", match.range.first).takeIf { it >= 0 } ?: return null
        val bodyStart = html.indexOf('>', start).takeIf { it >= 0 } ?: return null
        val end = html.indexOf("</script>", bodyStart).takeIf { it >= 0 } ?: return null
        return html.substring(bodyStart + 1, end)
    }

    /**
     * Reads the encryption parameters of a chapter page.
     *
     * @return `null` when the page is a captcha or a degraded copy: both carry no config array.
     */
    fun config(html: String): ReaderPage? {
        val chunks = CONFIG_ARRAY.find(html)?.groupValues?.get(1) ?: return null
        val json = runCatching {
            String(Base64.decode(chunks.replace(CHUNK_NOISE, ""), Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val config = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tagName = config.optString("t").takeIf { it.isNotEmpty() } ?: return null
        val payloadAttribute = config.optString("d").takeIf { it.isNotEmpty() } ?: return null
        val indicatorAttribute = config.optString("ia").takeIf { it.isNotEmpty() } ?: return null

        val markup = Regex("<" + Regex.escape(tagName) + """\b[^>]*>""").find(html)?.value ?: return null
        return ReaderPage(
            tagName = tagName,
            payloadAttribute = payloadAttribute,
            indicatorAttribute = indicatorAttribute,
            offset = config.optInt("s", 0),
            payload = attribute(markup, "data-$payloadAttribute"),
            indicator = attribute(markup, "data-$indicatorAttribute"),
        )
    }

    /**
     * Decrypts the ordered page list of a chapter.
     *
     * @param page Config and ciphertext read from the chapter HTML.
     * @param alphabet The 62-character substitution alphabet of the page's `reader.js` build.
     * @return The CDN paths as published, or `null` when the alphabet does not belong to this build
     * (the decoded bytes then stop being JSON) or the payload is truncated.
     */
    fun decrypt(page: ReaderPage, alphabet: String): List<String>? {
        val json = rawJson(page, alphabet) ?: return null
        val body = runCatching { JSONObject(json) }.getOrNull() ?: return null

        val key = runCatching {
            String(Base64.decode(page.indicator, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val array = body.optJSONArray(key) ?: return null

        val adIndex = AD_INDEX.find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val paths = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            if (index == adIndex) continue
            val path = array.optString(index, "")
            // Recent payloads keep a PNG placeholder in the list; that path answers 404 on the CDN
            // and would only show up as a broken page.
            if (path.isEmpty() || path.substringBefore('?').lowercase().endsWith(".png")) continue
            paths += path
        }
        return paths.takeIf { it.isNotEmpty() }
    }

    /** The decrypted JSON document, for diagnostics; `null` when the alphabet does not fit. */
    fun rawJson(page: ReaderPage, alphabet: String): String? {
        if (alphabet.length != 62) return null
        if (page.payload.length <= page.offset) return null
        val base64 = unsubstituted(page, alphabet)
        return runCatching {
            String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.startsWith("{") }
    }

    /** Maps the ciphertext back to the standard base64 alphabet. */
    private fun unsubstituted(page: ReaderPage, alphabet: String): String {
        val map = IntArray(128) { -1 }
        for (index in alphabet.indices) {
            val code = alphabet[index].code
            if (code < 128) map[code] = STANDARD_ALPHABET[index].code
        }
        val source = page.payload
        val buffer = StringBuilder(source.length - page.offset)
        for (index in page.offset until source.length) {
            val char = source[index]
            val code = char.code
            buffer.append(if (code < 128 && map[code] >= 0) map[code].toChar() else char)
        }
        return buffer.toString()
    }

    /** Reads one attribute value out of a start tag, without building a DOM for it. */
    private fun attribute(markup: String, name: String): String {
        val match = Regex(Regex.escape(name) + """\s*=\s*"([^"]*)"""").find(markup) ?: return ""
        return match.groupValues[1]
    }
}
