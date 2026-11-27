package eu.kanade.tachiyomi.extension.fr.japscan

import android.util.Log
import keiyoushi.utils.runWebView
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Recovers the substitution alphabet of a `reader.js` build.
 *
 * The alphabet is not a literal of the bundle: Japscan ships 34 decoy alphabets of 62 characters
 * and assembles the real one at runtime, so extracting it statically cannot work. It is however a
 * constant of the build — measured invariant to the page config, the payload and the user agent —
 * so it only has to be recovered once per `reader.js` hash.
 *
 * The recovery loads the chapter in a WebView whose only difference from the reader's own is a
 * `String.prototype.replace` hook, installed before any page script runs. The site's bundle then
 * decrypts the page as usual and the hook reads the alphabet out of the substitution it performs.
 * That is one page load per build, not per chapter: every later chapter is read by [JapscanCrypto].
 */
internal object TableLearning {
    /** The only valid alphabet length. */
    private const val ALPHABET_LENGTH = 62

    /**
     * Loads [chapterUrl] with [headers] and returns the alphabet of the bundle it runs.
     *
     * @return The 62-character alphabet, or `null` when the page never decrypted anything (gated
     * page, Cloudflare challenge, bundle too slow for the timeout).
     */
    suspend fun learn(chapterUrl: String, headers: Map<String, String>): String? {
        val userAgent = headers["User-Agent"]
        return runCatching {
            runWebView<String?>(timeout = 40.seconds) {
                userAgent?.let { this.userAgent = it }
                injectAtDocumentStart(HOOK)
                onPageFinished {
                    var rounds = 0
                    poll(200.milliseconds) {
                        rounds++
                        evaluateJs("String(window.__japscanAlphabet || '')") { value ->
                            val alphabet = value.trim().trim('"')
                            if (alphabet.length == ALPHABET_LENGTH) {
                                Log.d("JapscanReader", "learn: alphabet found after ${rounds * 200}ms")
                                resolve(alphabet)
                            } else if (rounds > 100) {
                                Log.w("JapscanReader", "learn: no alphabet after ${rounds * 200}ms")
                                resolve(null)
                            }
                        }
                    }
                }
                loadUrl(chapterUrl, headers)
            }
        }.onFailure {
            Log.w("JapscanReader", "learn: failed: ${it::class.java.simpleName} ${it.message}")
        }.getOrNull()
    }

    /**
     * Captures the alphabet by observing the bundle's own character substitution.
     *
     * It runs before any page script, so the site's anti-tamper check (`String.prototype.replace`
     * must look native) has to keep passing: the hook reports the native source and keeps the
     * native arity.
     */
    private const val HOOK = """
        (function () {
          window.__japscanAlphabet = '';
          var STD = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
          var original = String.prototype.replace;
          var hooked = function (pattern, replacement) {
            try {
              if (pattern && pattern.source && pattern.source.indexOf('A-Z0-9') !== -1 &&
                  typeof replacement === 'function') {
                var map = {};
                for (var i = 0; i < STD.length; i++) {
                  var clear = STD[i];
                  var cipher = replacement(clear);
                  if (typeof cipher === 'string' && cipher.length === 1) map[cipher] = clear;
                }
                if (Object.keys(map).length === 62) {
                  var alphabet = '';
                  for (var j = 0; j < STD.length; j++) alphabet += map[STD[j]];
                  window.__japscanAlphabet = alphabet;
                }
              }
            } catch (e) {}
            return original.apply(this, arguments);
          };
          hooked.toString = function () { return 'function replace() { [native code] }'; };
          String.prototype.replace = hooked;
        })();
    """
}
