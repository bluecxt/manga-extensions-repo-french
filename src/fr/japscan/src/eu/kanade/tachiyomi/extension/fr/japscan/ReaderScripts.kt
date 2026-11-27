package eu.kanade.tachiyomi.extension.fr.japscan

// Desktop Chrome UA matching what the descrambler's working code path expects (the source's
// own UA is Android-mobile flavored, which makes the descrambler take a one-tile-per-page path).
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

// Satisfies the reader's anti-adblock check (`window.aclib`) before it runs; without it the
// reader wipes the descrambled canvases because the real ad script is blocked.
internal val ACLIB_STUB = """
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
""".trimIndent()

// Paginated mode: minimal createObjectURL capture. The descrambler surfaces each composited
// page as a `blob:` URL, capturing the latest one per page-click is enough.
internal fun paginatedHook(interfaceName: String) = $$"""
                                (function(){
                                    if (window.__japscanHooked) return;
                                    window.__japscanHooked = true;
                                    // Same logging channel as the long-strip hooks: without it every
                                    // diagnostic emitted by the paginated driver was swallowed.
                                    try {
                                        var jl = window.$$interfaceName && window.$$interfaceName.log;
                                        window.__jlog = jl
                                            ? function(){ try { var p = []; for (var i = 0; i < arguments.length; i++) p.push(String(arguments[i])); window.$$interfaceName.log(p.join(' ')); } catch(e) {} }
                                            : function(){ try { console.log.apply(console, arguments); } catch(e) {} };
                                    } catch(e) { window.__jlog = function(){}; }
                                    window.__japscanLastBlob = null;
                                    window.__japscanBlobs = window.__japscanBlobs || {};
                                    // Diagnose how the reader builds its page image: a canvas re-encode
                                    // would mean our capture inherits that quality loss.
                                    try {
                                        var _origToBlob = HTMLCanvasElement.prototype.toBlob;
                                        HTMLCanvasElement.prototype.toBlob = function(cb, type, quality){
                                            var self = this;
                                            var wrapped = function(b){
                                                try {
                                                    window.__jlog('[japscan] canvas.toBlob ' + self.width + 'x' + self.height +
                                                        ' type=' + (type || 'default') + ' q=' + (quality === undefined ? '-' : quality) +
                                                        ' size=' + (b ? b.size : -1));
                                                } catch(e) {}
                                                return cb ? cb(b) : undefined;
                                            };
                                            return _origToBlob.call(this, wrapped, type, quality);
                                        };
                                        try {
                                            HTMLCanvasElement.prototype.toBlob.toString = function(){
                                                return 'function toBlob() { [native code] }';
                                            };
                                        } catch(e) {}
                                    } catch(e) {}
                                    var _orig = URL.createObjectURL.bind(URL);
                                    URL.createObjectURL = function(obj){
                                        var u = _orig(obj);
                                        try {
                                            if (obj && obj.type && /^image\//.test(obj.type)) {
                                                window.__japscanLastBlob = u;
                                                // Keep the Blob itself: reading it directly skips the
                                                // ~150 ms blob: fetch before every page.
                                                window.__japscanLastBlobObj = obj;
                                                // Cheap page counter the driver can poll without
                                                // touching the Blob.
                                                window.__japscanCreated = (window.__japscanCreated || 0) + 1;
                                                // Hand the page over the moment it exists: the driver
                                                // encodes it and the Blob is released, so a 195-page
                                                // chapter never sits on the JS heap. Until the driver
                                                // arms the pump, the blob is parked for it to drain.
                                                try {
                                                    if (window.__japscanPump) window.__japscanPump(obj);
                                                    else window.__japscanBlobs[u] = obj;
                                                } catch(e) {}
                                                try {
                                                    window.__japscanSeen = (window.__japscanSeen || 0) + 1;
                                                    if (window.__japscanSeen <= 3 || window.__japscanSeen % 25 === 0) {
                                                        window.__jlog('[japscan] page ' + window.__japscanSeen +
                                                            ' blob type=' + obj.type + ' size=' + obj.size);
                                                    }
                                                } catch(e) {}
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
""".trimIndent()

internal fun webtoonHooks(interfaceName: String) = $$"""
                            (function(){
                                // Pin a logging channel through the Java bridge BEFORE the
                                // site has a chance to silence `console.log` (some pages do
                                // `console.log = function(){}` once their reader scripts run,
                                // which would swallow every diagnostic the rest of the driver
                                // emits via `console.log`).
                                try {
                                    var jl = window.$$interfaceName && window.$$interfaceName.log;
                                    if (jl) {
                                        window.__jlog = function(){
                                            try {
                                                var parts = [];
                                                for (var i = 0; i < arguments.length; i++) parts.push(String(arguments[i]));
                                                window.$$interfaceName.log(parts.join(' '));
                                            } catch(e) {}
                                        };
                                    } else {
                                        window.__jlog = function(){ try { console.log.apply(console, arguments); } catch(e) {} };
                                    }
                                } catch(e) {
                                    window.__jlog = function(){};
                                }
                                window.__jlog('[japscan] onPageStarted JS entered, alreadyHooked=' + (window.__japscanHooked === true) + ' url=' + location.href);
                                if (window.__japscanHooked) return;
                                window.__japscanHooked = true;

                                // Patch table: maps each patched function to the canonical
                                // native-code string for its name. We override
                                // Function.prototype.toString so the site sees the original
                                // native source whenever it stringifies one of our hooks —
                                // the reader trips `__poisoned = true` if it spots a
                                // tampered built-in (it explicitly checks `replace`,
                                // `atob` and friends, and likely checks `attachShadow`
                                // and the `shadowRoot` getter as well).
                                var masked = new WeakMap();
                                var origFnToString = Function.prototype.toString;
                                function mask(fn, name){
                                    try { masked.set(fn, 'function ' + name + '() { [native code] }'); } catch(e) {}
                                    return fn;
                                }
                                var patchedToString = function toString(){
                                    var m = masked.get(this);
                                    if (m !== undefined) return m;
                                    return origFnToString.call(this);
                                };
                                Function.prototype.toString = patchedToString;
                                mask(patchedToString, 'toString');

                                // Force every shadow root open at attach time so we can
                                // read its canvases later — but track which ones the
                                // caller *requested* be closed, and hide those from the
                                // public `Element.prototype.shadowRoot` getter. The site
                                // sees `el.shadowRoot === null` for its `<w-f1db5>` hosts
                                // (matching the unpatched behavior); only our compositor,
                                // via `window.__japscanShadowRootFor(el)`, can reach them.
                                try {
                                    var closedRoots = new WeakMap();
                                    var _attach = Element.prototype.attachShadow;
                                    var newAttach = function attachShadow(init){
                                        var requested = (init && init.mode) || 'open';
                                        var opts = {};
                                        if (init) { for (var k in init) opts[k] = init[k]; }
                                        opts.mode = 'open';
                                        var sr = _attach.call(this, opts);
                                        if (requested === 'closed') {
                                            try { closedRoots.set(this, sr); } catch(e) {}
                                        }
                                        return sr;
                                    };
                                    Element.prototype.attachShadow = newAttach;
                                    mask(newAttach, 'attachShadow');

                                    var origDesc = Object.getOwnPropertyDescriptor(Element.prototype, 'shadowRoot');
                                    var origGetter = origDesc && origDesc.get;
                                    var newGetter = function get(){
                                        if (closedRoots.has(this)) return null;
                                        return origGetter ? origGetter.call(this) : null;
                                    };
                                    Object.defineProperty(Element.prototype, 'shadowRoot', {
                                        get: newGetter,
                                        configurable: true,
                                    });
                                    mask(newGetter, 'get shadowRoot');

                                    window.__japscanShadowRootFor = function(el){
                                        try {
                                            if (closedRoots.has(el)) return closedRoots.get(el);
                                            return origGetter ? origGetter.call(el) : el.shadowRoot;
                                        } catch(e) { return null; }
                                    };
                                } catch(e) {
                                    window.__jlog('[japscan] shadow hook failed: ' + e);
                                }

                                // Android throttles requestAnimationFrame for non-visible WebViews
                                // (our WebView is detached, so it's never visible). The reader
                                // drives its tile-creation loop on RAF; without ticks it renders
                                // only the first tile per page and stalls. Force RAF to fire via
                                // setTimeout so the loop keeps progressing.
                                try {
                                    var _rAF = window.requestAnimationFrame;
                                    var newRAF = function requestAnimationFrame(cb){
                                        return setTimeout(function(){
                                            try { cb(performance.now()); } catch(e) {}
                                        }, 16);
                                    };
                                    window.requestAnimationFrame = newRAF;
                                    mask(newRAF, 'requestAnimationFrame');
                                } catch(e) {
                                    window.__jlog('[japscan] rAF hook failed: ' + e);
                                }

                                // Track canvas drawing activity so the compositor can wait for
                                // descrambling to settle before reading pixels. Wrapping
                                // drawImage is detectable via .toString — masked above.
                                try {
                                    window.__japscanLastDraw = 0;
                                    var _drawImage = CanvasRenderingContext2D.prototype.drawImage;
                                    var newDrawImage = function drawImage(){
                                        try {
                                            window.__japscanLastDraw = Date.now();
                                        } catch(e) {}
                                        return _drawImage.apply(this, arguments);
                                    };
                                    CanvasRenderingContext2D.prototype.drawImage = newDrawImage;
                                    mask(newDrawImage, 'drawImage');
                                } catch(e) {
                                    window.__jlog('[japscan] draw hook failed: ' + e);
                                }

                                // Spoof JS-side environment to match a desktop Chrome on this
                                // chapter. The WebView's native viewport/screen/touch signals
                                // tell the descrambler "you're a phone" and it takes the
                                // single-tile-per-page mobile path. Override the values that
                                // the differential probe identified:
                                //   innerWidth/innerHeight, devicePixelRatio,
                                //   screen.width/height, navigator.platform/maxTouchPoints,
                                //   matchMedia for (pointer: fine), (hover: hover),
                                //   and (min-width: <N>px).
                                //
                                // The UA is spoofed HERE, not at the WebSettings level:
                                // WebSettings keeps the Android UA on purpose so Cloudflare
                                // doesn't issue a fresh challenge for this request, and only
                                // the JS-visible navigator.userAgent reads as desktop.
                                // Anything not in the list above was tried and dropped —
                                // don't re-add fake plugins/chrome/webdriver on spec.
                                try {
                                    var defineGetter = function(obj, name, val){
                                        try {
                                            Object.defineProperty(obj, name, {
                                                get: function(){ return val; },
                                                configurable: true,
                                            });
                                        } catch(e) { window.__jlog('[japscan] defineGetter ' + name + ' failed: ' + e); }
                                    };
                                    defineGetter(window, 'innerWidth', 2560);
                                    defineGetter(window, 'innerHeight', 1214);
                                    defineGetter(window, 'devicePixelRatio', 0.75);
                                    defineGetter(window.screen, 'width', 1920);
                                    defineGetter(window.screen, 'height', 1080);
                                    defineGetter(window.screen, 'availWidth', 1920);
                                    defineGetter(window.screen, 'availHeight', 1040);
                                    defineGetter(navigator, 'userAgent', '$$DESKTOP_USER_AGENT');
                                    defineGetter(navigator, 'appVersion', '$$DESKTOP_USER_AGENT'.substring(8));
                                    defineGetter(navigator, 'platform', 'Win32');
                                    defineGetter(navigator, 'maxTouchPoints', 0);
                                    defineGetter(navigator, 'hardwareConcurrency', 12);
                                    // Remove ontouchstart so feature-detect-based mobile
                                    // branches see a non-touch device.
                                    try { delete window.ontouchstart; } catch(e) {}

                                    var _mm = window.matchMedia.bind(window);
                                    var newMM = function matchMedia(q){
                                        var orig = _mm(q);
                                        var shouldForceTrue = (
                                            q === '(pointer: fine)' ||
                                            q === '(hover: hover)' ||
                                            /\(min-width:\s*\d+px\)/.test(q) ||
                                            /\(min-device-width:\s*\d+px\)/.test(q)
                                        );
                                        if (!shouldForceTrue) return orig;
                                        return new Proxy(orig, {
                                            get: function(t, k){
                                                if (k === 'matches') return true;
                                                var v = t[k];
                                                return typeof v === 'function' ? v.bind(t) : v;
                                            },
                                        });
                                    };
                                    window.matchMedia = newMM;
                                    mask(newMM, 'matchMedia');
                                } catch(e) {
                                    window.__jlog('[japscan] env spoof failed: ' + e);
                                }

                                window.__jlog('[japscan] hooks installed');
                            })();
""".trimIndent()

internal fun paginatedDriver(interfaceName: String, uploadUrl: String) = $$"""
                                (async function(){
                                    // onPageFinished fires more than once per load (redirects,
                                    // iframes, in-page navigations). Without this guard a second
                                    // driver races the first and saves every page twice.
                                    if (window.__japscanDriverStarted) return;
                                    window.__japscanDriverStarted = true;
                                    var sleep = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };
                                    // Loopback receiver URL, empty when it could not be started. Any
                                    // upload failure permanently falls back to the bridge.
                                    var uploadBase = "$$uploadUrl";
                                    var withTimeout = function(p, ms){
                                        return Promise.race([p, new Promise(function(_, rej){
                                            setTimeout(function(){ rej(new Error('upload timeout')); }, ms);
                                        })]);
                                    };
                                    var nextBtn = document.getElementById('block-right');
                                    var prevBtn = document.getElementById('block-left');
                                    // Upper bound for the page cap when the reader publishes no count.
                                    var MAX_PAGES = 500;

                                    // The reader populates #pages from its own bootstrap, which can land
                                    // after onPageFinished. Reading it too early reported total = 0 and
                                    // gave up on a chapter that was merely still mounting.
                                    // A cold renderer needs well over 8 s to run the reader bootstrap,
                                    // so the wait is 20 s and it pings the watchdog: without the ping
                                    // the capture is cut after 10 s of silence.
                                    // Waits for the reader to publish its page list. Bails out as
                                    // soon as a page renders, page count or not.
                                    async function mountWait(rounds) {
                                        for (var m = 0; m < rounds; m++) {
                                            var found = document.getElementById('pages');
                                            if (found && found.options && found.options.length > 0) return found;
                                            if (created() > 0) return null;
                                            if (m % 10 === 0) {
                                                try { window.$$interfaceName.keepAlive(); } catch(e) {}
                                            }
                                            // Cloudflare shows up within a second; reacting at
                                            // once hands the window to the user instead of
                                            // burning the whole mount budget first.
                                            if (m >= 10) {
                                                try {
                                                    if (/just a moment|un instant|attention required/i.test(document.title || '')) {
                                                        window.__jlog('[japscan] cloudflare challenge, handing over');
                                                        return null;
                                                    }
                                                } catch(e) {}
                                            }
                                            // The captcha page never publishes a page list:
                                            // react as soon as it shows up instead of burning
                                            // the full twenty-second wait.
                                            if (m >= 20) {
                                                try {
                                                    if (document.querySelector('script[src*="captcha"], link[href*="captcha"]')) {
                                                        window.__jlog('[japscan] captcha page, no point waiting');
                                                        return null;
                                                    }
                                                } catch(e) {}
                                            }
                                            if (m === 30) {
                                                window.__jlog('[japscan] still mounting: selects=' +
                                                    document.querySelectorAll('select').length +
                                                    ' pagesEl=' + !!document.getElementById('pages') +
                                                    ' title=' + document.title);
                                            }
                                            await sleep(100);
                                        }
                                        return null;
                                    }

                                    var sel = await mountWait(200);

                                    // The site sometimes answers the navigation with a degraded copy of
                                    // the chapter page (real title, no page list), while a plain fetch
                                    // from this very document gets the complete one. Ask again and swap
                                    // the document in place: the hooks live on the window, so they
                                    // survive the swap.
                                    if (!sel && created() === 0) {
                                        // Cloudflare's interstitial is not the site captcha: it needs
                                        // a real browser window, exactly like the app does for any
                                        // other request it cannot get through.
                                        try {
                                            if (/just a moment|un instant|attention required/i.test(document.title || '')) {
                                                window.__jlog('[japscan] cloudflare challenge page');
                                                window.$$interfaceName.cloudflareNeeded();
                                            }
                                        } catch(e) {}
                                        // The site gates the reader behind its own captcha on some
                                        // loads. The OkHttp probe never sees it (it answers the
                                        // probe with the plain page), so the WebView has to say so.
                                        try {
                                            var captchaUp = !!window.__captcha ||
                                                !!document.querySelector('script[src*="captcha"]') ||
                                                !!document.querySelector('link[href*="captcha"]');
                                            if (captchaUp) {
                                                window.__jlog('[japscan] captcha page detected');
                                                window.$$interfaceName.captchaNeeded();
                                                // Hand the document over: the strips are in it and the
                                                // Kotlin solver already knows how to order them. Awaited
                                                // so the HTML is there before this driver finishes.
                                                try {
                                                    var captchaResp = await fetch(location.href, { credentials: 'same-origin' });
                                                    window.$$interfaceName.captchaPage(await captchaResp.text());
                                                } catch(e) {
                                                    window.__jlog('[japscan] captcha page fetch failed: ' + e);
                                                }
                                            }
                                        } catch(e) {}
                                        var recovered = false;
                                        try {
                                            var refetched = await fetch(location.href, { credentials: 'same-origin' });
                                            var pageHtml = await refetched.text();
                                            var pagesBlock = pageHtml.match(/id="pages"[^>]*>([\s\S]*?)<\/select>/);
                                            var pageOptions = pagesBlock ? (pagesBlock[1].match(/<option/g) || []).length : 0;
                                            // The served page never carries #pages options: the reader fills
                                            // that list itself. What the good page does carry is the
                                            // encrypted page config, so that is what tells them apart.
                                            var hasConfig = /var\s+\w+\s*=\s*\["eyJkIj/.test(pageHtml);
                                            window.__jlog('[japscan] re-fetch self: status=' + refetched.status +
                                                ' len=' + pageHtml.length + ' pageOptions=' + pageOptions +
                                                ' reader=' + (pageHtml.indexOf('single-reader') >= 0));
                                            if (hasConfig) {
                                                document.open();
                                                document.write(pageHtml);
                                                document.close();
                                                recovered = true;
                                                window.__jlog('[japscan] degraded page replaced by the fetched one');
                                            }
                                        } catch(e) {
                                            window.__jlog('[japscan] re-fetch failed: ' + e);
                                        }
                                        if (recovered) sel = await mountWait(200);
                                    }
                                    // expected = 0 means the reader never published a page count. That is
                                    // not "zero pages": the reader still composites them and the hook
                                    // still sees every one, so the capture runs without the count.
                                    var expected = sel ? sel.options.length : 0;
                                    if (expected === 0) {
                                        // Tell an empty chapter apart from a page that never mounted:
                                        // whatever the reader displays is the only clue we get.
                                        var shown = '';
                                        var html = '';
                                        try {
                                            shown = ((document.body && document.body.innerText) || '')
                                                .replace(/\s+/g, ' ').slice(0, 120);
                                            html = (document.documentElement && document.documentElement.outerHTML || '').slice(0, 300);
                                        } catch(e) {}
                                        window.__jlog('[japscan] no page list, ready=' + document.readyState +
                                            ' nodes=' + document.querySelectorAll('*').length +
                                            ' htmlLen=' + ((document.documentElement && document.documentElement.outerHTML) || '').length +
                                            ' imgs=' + document.images.length +
                                            ' url=' + location.href +
                                            ' ref=' + (document.referrer || 'none') +
                                            ' text=' + shown);
                                        window.__jlog('[japscan] page html: ' + html);
                                    }
                                    var total = expected || MAX_PAGES;
                                    window.__jlog('[japscan] paginated mode, total = ' + (expected || 'unknown') +
                                        ', next=' + !!nextBtn + ', prev=' + !!prevBtn +
                                        ', title=' + document.title);

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
                                            window.__jlog('[japscan] nav failed: ' + e);
                                        }
                                    }

                                    function created(){ return window.__japscanCreated || 0; }

                                    async function waitCreated(min, timeoutMs){
                                        var w = 0;
                                        while (created() < min && w < timeoutMs) {
                                            await sleep(50); w += 50;
                                        }
                                        return created() >= min;
                                    }

                                    // ---- push at creation ---------------------------------------------
                                    // The reader composites each page onto a canvas and hands the
                                    // result to URL.createObjectURL as an image/jpeg Blob. The hook
                                    // forwards every Blob here the instant it is created, so pages are
                                    // harvested without any navigation, and the Blob can be released
                                    // right away instead of being kept on the JS heap.
                                    //
                                    // Call order IS page order: the reader renders page N+1 before
                                    // showing it, and the hook preserves creation order.
                                    //
                                    // The chain is deliberately sequential: a 3-deep FileReader
                                    // pipeline was measured at 9.7s against 9.3s here, because the
                                    // encode (11ms) is already hidden behind the reader's own render
                                    // (48ms/page) and only the bridge call defines the order.
                                    var pumped = 0;
                                    var sumRead = 0;
                                    var sumBridge = 0;
                                    var sumUpload = 0;
                                    var seen = (typeof WeakSet === 'function') ? new WeakSet() : null;
                                    var chain = Promise.resolve();
                                    // The reader creates the blob of a page twice on some renders
                                    // (the same bytes, a few ms apart). Keeping both shifts every
                                    // following page by one, so an exact repeat is dropped.
                                    var lastSize = -1;
                                    var lastData = null;

                                    function encode(blob){
                                        return new Promise(function(res, rej){
                                            var fr = new FileReader();
                                            fr.onload = function(){ res(fr.result); };
                                            fr.onerror = rej;
                                            fr.readAsDataURL(blob);
                                        });
                                    }

                                    function push(blob){
                                        if (!blob || !blob.size) return;
                                        // A repeat consumes an index too: leaving no margin here
                                        // dropped the last real page of every chapter.
                                        if (pumped >= total + 8) return;
                                        if (seen) { if (seen.has(blob)) return; seen.add(blob); }
                                        chain = chain.then(async function(){
                                            // A repeat upload consumes an index, so the cap must
                                            // leave room or the last real page is never sent.
                                            if (pumped >= total + 8) return;
                                            try {
                                                // A local socket carries the raw JPEG: no base64,
                                                // no 750 KB string across the bridge. The index in
                                                // the URL carries the page order.
                                                if (uploadBase) {
                                                    try {
                                                        var u0 = Date.now();
                                                        var resp = await withTimeout(
                                                            fetch(uploadBase + '/page/' + (pumped + 1), { method: 'POST', body: blob }),
                                                            5000,
                                                        );
                                                        var u1 = Date.now();
                                                        if (!resp || !resp.ok) throw new Error('HTTP ' + (resp && resp.status));
                                                        pumped++;
                                                        sumUpload += u1 - u0;
                                                        if (pumped % 25 === 0) {
                                                            window.__jlog('[japscan] socket page ' + pumped + ' ' +
                                                                (u1 - u0) + 'ms size=' + blob.size);
                                                        }
                                                        return;
                                                    } catch(e) {
                                                        window.__jlog('[japscan] socket upload failed, using the bridge: ' + e);
                                                        uploadBase = '';
                                                    }
                                                }
                                                var t0 = Date.now();
                                                var d = await encode(blob);
                                                var t1 = Date.now();
                                                if (typeof d !== 'string' || d.indexOf('data:image/') !== 0) return;
                                                if (blob.size === lastSize && d === lastData) {
                                                    window.__jlog('[japscan] duplicate page skipped (size=' + blob.size + ')');
                                                    return;
                                                }
                                                lastSize = blob.size;
                                                lastData = d;
                                                window.$$interfaceName.savePage(d.substring(d.indexOf(',') + 1));
                                                var t2 = Date.now();
                                                pumped++;
                                                sumRead += t1 - t0;
                                                sumBridge += t2 - t1;
                                                if (pumped % 25 === 0) {
                                                    window.__jlog('[japscan] pump page ' + pumped + ' read=' + (t1 - t0) +
                                                        'ms bridge=' + (t2 - t1) + 'ms size=' + blob.size);
                                                }
                                            } catch(e) {
                                                window.__jlog('[japscan] pump failed: ' + e);
                                            }
                                        });
                                    }
                                    window.__japscanPump = push;

                                    // Pages the reader rendered before this driver ran.
                                    try {
                                        var already = Object.keys(window.__japscanBlobs || {});
                                        for (var ai = 0; ai < already.length; ai++) {
                                            push(window.__japscanBlobs[already[ai]]);
                                            delete window.__japscanBlobs[already[ai]];
                                        }
                                        window.__jlog('[japscan] pump armed with ' + already.length + ' pre-rendered page(s)');
                                    } catch(e) {}

                                    // ---- harvest ------------------------------------------------------
                                    // Wait for the pre-render to stop producing pages instead of
                                    // clicking blindly: the reader renders ahead of the visible page.
                                    var mark = created();
                                    for (var s = 0; s < 60; s++) {
                                        await sleep(500);
                                        var now = created();
                                        if (now === mark) break;
                                        mark = now;
                                    }
                                    await chain;
                                    window.__jlog('[japscan] pre-render settled: ' + pumped + ' / ' + (expected || '?') +
                                        ' page(s), created=' + created());

                                    // Pre-rendering is windowed on long chapters: walk forward so the
                                    // reader composites the rest. The wait is the reader's own debounce,
                                    // and the encode of the previous page runs meanwhile.
                                    if (expected > 0) {
                                        var stalls = 0;
                                        for (var i = 1; i < expected && created() < expected && stalls < 12; i++) {
                                            var before = created();
                                            nav(nextBtn, 'ArrowRight');
                                            if (await waitCreated(before + 1, 900)) stalls = 0; else stalls++;
                                        }
                                        await chain;
                                    }
                                    window.__jlog('[japscan] walk done: ' + pumped + ' / ' + (expected || '?') +
                                        ' page(s), created=' + created());

                                    // Complete when every announced page is in, or, with no count, when
                                    // the reader stopped producing pages at all.
                                    var complete = expected > 0 ? pumped >= expected - 1 : pumped > 0;
                                    if (complete) {
                                        window.__jlog('[japscan] done (pump), ' + pumped + ' / ' + (expected || '?') + ' pages saved, upload_avg=' +
                                            (pumped ? Math.round(sumUpload / pumped) : 0) + 'ms read_avg=' +
                                            (pumped ? Math.round(sumRead / pumped) : 0) + 'ms bridge_avg=' +
                                            (pumped ? Math.round(sumBridge / pumped) : 0) + 'ms');
                                        // The last blob's upload can still be in flight: let the
                                        // chain settle before telling Kotlin the capture is over,
                                        // or that page lands after the snapshot and is lost.
                                        await sleep(250);
                                        await chain;
                                        try { window.$$interfaceName.passDone(); } catch(e) {}
                                        return;
                                    }

                                    // ---- fallback -----------------------------------------------------
                                    // The pump did not cover the chapter (unexpected render order or a
                                    // page the reader never composites). Throw the partial harvest away
                                    // and replay the click loop, which captures strictly in page order.
                                    if (expected === 0) {
                                        // The click loop walks the page list, so it cannot run without
                                        // one. Keep what the reader rendered.
                                        window.__jlog('[japscan] no page count, keeping the ' + pumped + ' rendered page(s)');
                                        try { window.$$interfaceName.passDone(); } catch(e) {}
                                        return;
                                    }
                                    window.__jlog('[japscan] pump incomplete (' + pumped + ' / ' + expected + '), fallback to the click loop');
                                    window.__japscanPump = null;
                                    try { window.$$interfaceName.resetPages(); } catch(e) {}
                                    pumped = 0;

                                    async function saveBlobUrl(u){
                                        if (!u) return false;
                                        try {
                                            var t0 = Date.now();
                                            var b = window.__japscanLastBlobObj;
                                            if (!b) {
                                                var r = await fetch(u);
                                                b = await r.blob();
                                            }
                                            var t1 = Date.now();
                                            var d = await new Promise(function(res, rej){
                                                var fr = new FileReader();
                                                fr.onload = function(){ res(fr.result); };
                                                fr.onerror = rej;
                                                fr.readAsDataURL(b);
                                            });
                                            var t2 = Date.now();
                                            if (typeof d === 'string' && d.indexOf('data:image/') === 0) {
                                                window.$$interfaceName.savePage(d.substring(d.indexOf(',') + 1));
                                                var t3 = Date.now();
                                                window.__jlog('[japscan] saveBlob fetch=' + (t1 - t0) +
                                                    'ms read=' + (t2 - t1) + 'ms bridge=' + (t3 - t2) +
                                                    'ms chars=' + d.length);
                                                return true;
                                            }
                                        } catch(e) {
                                            window.__jlog('[japscan] saveBlobUrl failed: ' + e);
                                        } finally {
                                            try { URL.revokeObjectURL(u); } catch(e) {}
                                            if (window.__japscanLastBlob === u) {
                                                window.__japscanLastBlob = null;
                                                window.__japscanLastBlobObj = null;
                                            }
                                        }
                                        return false;
                                    }

                                    async function waitForBlob(timeoutMs){
                                        var w = 0;
                                        while (!window.__japscanLastBlob && w < timeoutMs) {
                                            await sleep(100); w += 100;
                                        }
                                        if (!window.__japscanLastBlob) return -1;
                                        // The hook overwrites __japscanLastBlob as soon as the reader
                                        // composites a page. A 400 ms quiet window cost ~35 % of the
                                        // per-page time; 150 ms is enough to let the last tile land.
                                        var lastUrl = window.__japscanLastBlob;
                                        var stable = 0;
                                        while (stable < 150) {
                                            await sleep(50);
                                            if (window.__japscanLastBlob !== lastUrl) {
                                                lastUrl = window.__japscanLastBlob;
                                                stable = 0;
                                            } else {
                                                stable += 50;
                                            }
                                        }
                                        return w;
                                    }

                                    await waitForBlob(10000);
                                    window.__japscanLastBlob = null;

                                    nav(nextBtn, 'ArrowRight');
                                    await waitForBlob(8000);
                                    window.__japscanLastBlob = null;

                                    nav(prevBtn, 'ArrowLeft');
                                    var t0 = await waitForBlob(8000);
                                    var u0 = window.__japscanLastBlob;
                                    window.__japscanLastBlob = null;
                                    if (expected > 1) nav(nextBtn, 'ArrowRight');
                                    var saved = u0 ? ((await saveBlobUrl(u0)) ? 1 : 0) : 0;
                                    window.__jlog('[japscan] page 1 captured after ' + t0 + 'ms (saved=' + saved + ')');

                                    for (var j = 1; j < expected; j++) {
                                        var t = await waitForBlob(8000);
                                        var u = window.__japscanLastBlob;
                                        window.__japscanLastBlob = null;
                                        if (j < expected - 1) nav(nextBtn, 'ArrowRight');
                                        var ok = u ? await saveBlobUrl(u) : false;
                                        if (ok) saved++;
                                        window.__jlog('[japscan] page ' + (j + 1) + ' captured after ' + t + 'ms (saved=' + saved + ')');
                                    }

                                    window.__jlog('[japscan] done (click loop), ' + saved + ' / ' + expected + ' pages saved');
                                    try {
                                        window.$$interfaceName.passDone();
                                    } catch(e) {
                                        window.__jlog('[japscan] passDone failed: ' + e);
                                    }
                                })().catch(function(e){
                                    // A throw anywhere in this coroutine used to be silent: the
                                    // chapter was captured but never reported as done.
                                    try { window.__jlog('[japscan] driver crashed: ' + (e && e.message ? e.message : e)); } catch(_) {}
                                });
""".trimIndent()

internal fun webtoonDriver(interfaceName: String, urlSegment: String) = $$"""
                            (async function(){
                                // onPageFinished fires more than once per load (redirects,
                                // iframes, in-page navigations). Without this guard a second
                                // driver races the first and saves every tile twice.
                                if (window.__japscanDriverStarted) return;
                                window.__japscanDriverStarted = true;
                                var sleep = function(ms){ return new Promise(function(r){ setTimeout(r, ms); }); };
                                window.__jlog('[japscan] driver start');

                                // Collect every <canvas> reachable from `root`, descending
                                // through shadow roots (including the formerly-closed ones
                                // the attachShadow hook stashed in __japscanShadowRootFor).
                                var shadowFor = window.__japscanShadowRootFor || function(){ return null; };
                                function gatherCanvases(root, out) {
                                    if (!root) return;
                                    if (root.nodeType === 1 && root.tagName === 'CANVAS') {
                                        out.push(root);
                                    }
                                    var sr = null;
                                    try { sr = shadowFor(root); } catch(e) {}
                                    if (sr) {
                                        var sk = sr.children;
                                        if (sk) {
                                            for (var i = 0; i < sk.length; i++) gatherCanvases(sk[i], out);
                                        }
                                    }
                                    var ch = root.children;
                                    if (ch) {
                                        for (var j = 0; j < ch.length; j++) gatherCanvases(ch[j], out);
                                    }
                                }

                                // Long-page layout: `#d-img-N` holds a `cc…`-class wrapper whose
                                // direct <canvas> children are the descrambled vertical slices.
                                // Single source of truth for finding it — discoverTiles and
                                // expectedTileCount must agree, or a class rename desyncs them.
                                function findTileWrapper(container){
                                    if (!container) return null;
                                    for (var ci = 0; ci < container.children.length; ci++) {
                                        var ch = container.children[ci];
                                        if (ch.tagName === 'DIV' && ('' + ch.className).indexOf('cc') === 0) {
                                            return ch;
                                        }
                                    }
                                    return null;
                                }

                                // Discover tile groups in `#d-img-N`'s subtree. Canvases-first:
                                // walk canvases (works regardless of whether the host custom
                                // element is exposed in light DOM), group them by their shadow-root
                                // host, and treat each unique host as one tile.
                                //
                                // Returns `{ host, canvases }` records in stable order of first
                                // appearance. The host is the custom element (e.g. <b-123e6>,
                                // <w-f1db5> — the tag is randomized per series) whose `.canvas`
                                // own-property, when set, gives the descrambled bitmap; `canvases`
                                // is the layered set inside its shadow root.
                                //
                                // Discovery is STRUCTURAL, not readiness-based: we deliberately do
                                // NOT require `.canvas` to exist yet, because the descrambler is
                                // lazy and only renders a tile once it scrolls into view —
                                // chicken-and-egg if we filtered on `.canvas` here.
                                function discoverTiles(container){
                                    if (!container) return [];
                                    // Long pages: emit each slice as a standalone tile so
                                    // toDataURL runs per slice and the webtoon reader stitches them.
                                    var wrap = findTileWrapper(container);
                                    if (wrap) {
                                        var direct = [];
                                        for (var di = 0; di < wrap.children.length; di++) {
                                            var w = wrap.children[di];
                                            if (w.tagName === 'CANVAS' && w.width > 0 && w.height > 0) {
                                                direct.push(w);
                                            }
                                        }
                                        if (direct.length > 0) {
                                            var outArr = [];
                                            for (var di2 = 0; di2 < direct.length; di2++) {
                                                outArr.push({ host: direct[di2], canvases: [direct[di2]] });
                                            }
                                            return outArr;
                                        }
                                    }
                                    // Short-page / single-tile layout: canvases live inside a
                                    // custom-element host (e.g. <t-b8432>). Group them by
                                    // shadow-root host so each host yields one tile.
                                    var canvases = [];
                                    gatherCanvases(container, canvases);
                                    if (canvases.length === 0) return [];
                                    var map = new Map();
                                    var order = [];
                                    for (var i = 0; i < canvases.length; i++) {
                                        var c = canvases[i];
                                        if (c.width <= 0 || c.height <= 0) continue;
                                        var rn = c.getRootNode && c.getRootNode();
                                        var host = (rn && rn.host) ? rn.host : container;
                                        if (!map.has(host)) {
                                            map.set(host, []);
                                            order.push(host);
                                        }
                                        map.get(host).push(c);
                                    }
                                    var out = [];
                                    for (var k = 0; k < order.length; k++) {
                                        out.push({ host: order[k], canvases: map.get(order[k]) });
                                    }
                                    return out;
                                }

                                // Resolve a tile to a single canvas for export. Preference order:
                                //   1. `host.canvas` own-property — the descrambler's explicit
                                //      pointer to the final composited bitmap.
                                //   2. The lone canvas with `position: relative` in the layered
                                //      set — it establishes the visible tile and on inspection
                                //      consistently holds the real bitmap; siblings are decoys.
                                //   3. The first non-empty canvas — last-resort.
                                function pickTileCanvas(tile){
                                    // cc-wrapper slices are their own host — nothing to resolve.
                                    if (tile.host instanceof HTMLCanvasElement) return tile.host;
                                    try {
                                        if (tile.host && tile.host.canvas instanceof HTMLCanvasElement) {
                                            return tile.host.canvas;
                                        }
                                    } catch(e) {}
                                    var arr = tile.canvases || [];
                                    for (var i = 0; i < arr.length; i++) {
                                        var s = null;
                                        try { s = window.getComputedStyle(arr[i]); } catch(e) {}
                                        if (s && s.position === 'relative') return arr[i];
                                    }
                                    return arr[0] || null;
                                }

                                // Wait until at least one of the tile's canvases (or its
                                // host.canvas) has non-zero dimensions and the descrambler has
                                // gone quiet (no drawImage calls for ≥ quietMs).
                                async function waitForTileReady(tile, timeoutMs){
                                    var w = 0;
                                    var quietMs = 600;
                                    while (w < timeoutMs) {
                                        var anySized = false;
                                        try {
                                            if (tile.host && tile.host.canvas && tile.host.canvas.width > 0) anySized = true;
                                        } catch(e) {}
                                        if (!anySized) {
                                            var arr = tile.canvases || [];
                                            for (var i = 0; i < arr.length; i++) {
                                                if (arr[i].width > 0 && arr[i].height > 0) { anySized = true; break; }
                                            }
                                        }
                                        if (anySized) {
                                            // Honor the host's own ready flags if present.
                                            var hostReady = true;
                                            try {
                                                if (tile.host && ('cw' in tile.host)) {
                                                    hostReady = !!(tile.host.cw && tile.host.ch);
                                                }
                                            } catch(e) {}
                                            var sinceDraw = Date.now() - (window.__japscanLastDraw || 0);
                                            if (hostReady && sinceDraw >= quietMs) return w;
                                        }
                                        await sleep(150);
                                        w += 150;
                                    }
                                    return -1;
                                }

                                function tileToDataUri(tile){
                                    try {
                                        if (!window.__japscanImgProbe) {
                                            window.__japscanImgProbe = true;
                                            try {
                                                var imgs = Array.prototype.slice.call(document.images || []);
                                                var dataCount = imgs.filter(function(i){
                                                    return (i.currentSrc || i.src || '').indexOf('data:image/') === 0;
                                                }).length;
                                                var blobCount = imgs.filter(function(i){
                                                    return (i.currentSrc || i.src || '').indexOf('blob:') === 0;
                                                }).length;
                                                var httpCount = imgs.filter(function(i){
                                                    return /^https?:/.test(i.currentSrc || i.src || '');
                                                }).length;
                                                var lists = document.querySelectorAll('ss-list');
                                                var listInfo = 'none';
                                                if (lists.length > 0) {
                                                    var first = lists[0];
                                                    var kids = Array.prototype.slice.call(first.children || []);
                                                    var biggest = null;
                                                    var biggestArea = 0;
                                                    for (var k = 0; k < kids.length; k++) {
                                                        var r = kids[k].getBoundingClientRect();
                                                        var area = r.width * r.height;
                                                        if (area > biggestArea) { biggestArea = area; biggest = kids[k]; }
                                                    }
                                                    var canvasTotal = first.querySelectorAll('canvas').length;
                                                    var imgTotal = first.querySelectorAll('img').length;
                                                    listInfo = lists.length + 'x kids=' + kids.length +
                                                        ' canvases=' + canvasTotal + ' imgs=' + imgTotal +
                                                        ' biggest=' + (biggest ? (biggest.tagName + '.' + (biggest.className || '')) : 'none') +
                                                        ' area=' + Math.round(biggestArea);
                                                }
                                                window.__jlog('[japscan] probe: imgs total=' + imgs.length + ' data=' + dataCount +
                                                    ' blob=' + blobCount + ' http=' + httpCount + ' | ss-list ' + listInfo);
                                            } catch(e) {}
                                        }
                                        var c = pickTileCanvas(tile);
                                        if (!c || !c.width || !c.height) return null;
                                        var t0 = Date.now();
                                        var uri = c.toDataURL('image/jpeg', 0.92);
                                        window.__japscanEncodeMs = (window.__japscanEncodeMs || 0) + (Date.now() - t0);
                                        return uri;
                                    } catch(e) { return null; }
                                }

                                // Detect reader mode. Webtoon (long-strip) puts everything in
                                // `#full-reader` with one `<img id="img-N">` per page (each in
                                // its `<div id="d-img-N">` container) and hides `#single-reader`
                                // (class `d-none`); paginated mode is the opposite.
                                var fullReader = document.getElementById('full-reader');
                                var singleReader = document.querySelector('[id^="single-reader"]');
                                var dImgContainers = fullReader
                                    ? Array.from(fullReader.querySelectorAll('div[id^="d-img-"]'))
                                          .sort(function(a, b){
                                              return parseInt(a.id.replace('d-img-', ''), 10)
                                                   - parseInt(b.id.replace('d-img-', ''), 10);
                                          })
                                    : [];
                                var singleHidden = singleReader && singleReader.classList.contains('d-none');
                                var isWebtoon = dImgContainers.length > 0 && (singleHidden || !singleReader);
                                // The Kotlin side picked us based on the URL segment ($$urlSegment).
                                // Compare against what the DOM actually mounted so a misclassified
                                // series shows up in Logcat instead of producing silent garbage.
                                var urlHint = '$$urlSegment';
                                var hintMode = (urlHint === 'manhwa' || urlHint === 'manhua') ? 'webtoon' : 'paginated';
                                var domMode = isWebtoon ? 'webtoon' : 'paginated';
                                window.__jlog('[japscan] mode: webtoon=' + isWebtoon + ' pages=' + dImgContainers.length + ' urlHint=' + hintMode + ' dom=' + domMode + (hintMode !== domMode ? ' MISMATCH' : ''));



                                if (isWebtoon) {
                                    var total = dImgContainers.length;
                                    // Drop the hidden single-reader subtree to keep its stale
                                    // layer-canvases from adding memory pressure.
                                    if (singleReader && singleReader.parentNode) {
                                        try { singleReader.parentNode.removeChild(singleReader); } catch(e) {}
                                    }
                                    // For each `#d-img-N`: determine the expected tile count up
                                    // front (number of direct <canvas> children of the cc...
                                    // wrapper for long pages, else 1 for single-tile pages).
                                    // Try a one-pass capture first; if all expected tiles are
                                    // already drawn, we're done in <1s. Only fall back to the
                                    // slow scroll-and-discover loop if a tile is still missing.
                                    var tilesEmitted = 0;
                                    function expectedTileCount(container){
                                        var wrap = findTileWrapper(container);
                                        if (!wrap) return 1;
                                        var n = 0;
                                        for (var ki = 0; ki < wrap.children.length; ki++) {
                                            if (wrap.children[ki].tagName === 'CANVAS') n++;
                                        }
                                        return n > 0 ? n : 1;
                                    }
                                    async function captureOnce(container, pageLabel, savedHosts){
                                        var saved = 0;
                                        var tiles = discoverTiles(container);
                                        for (var ti = 0; ti < tiles.length; ti++) {
                                            var tile = tiles[ti];
                                            if (!tile.host || savedHosts.has(tile.host)) continue;
                                            var ready = await waitForTileReady(tile, 6000);
                                            if (ready < 0) continue;
                                            var picked = pickTileCanvas(tile);
                                            if (!picked || !picked.width || !picked.height) continue;
                                            var uri = tileToDataUri(tile);
                                            if (!uri) continue;
                                            try {
                                                var tBridge = Date.now();
                                                window.$$interfaceName.savePage(uri.substring(uri.indexOf(',') + 1));
                                                var bridgeMs = Date.now() - tBridge;
                                                savedHosts.add(tile.host);
                                                saved++;
                                                tilesEmitted++;
                                                window.__jlog('[japscan] ' + pageLabel + ' tile #' + savedHosts.size + ' saved (' + picked.width + 'x' + picked.height + ', ' + uri.length + ' chars, bridge=' + bridgeMs + 'ms, encodeTotal=' + window.__japscanEncodeMs + 'ms)');
                                            } catch(e) {
                                                window.__jlog('[japscan] ' + pageLabel + ' savePage failed: ' + e);
                                            }
                                        }
                                        return saved;
                                    }
                                    for (var i = 0; i < total; i++) {
                                        var container = dImgContainers[i];
                                        var pageLabel = 'd-img-' + i;
                                        var expected = expectedTileCount(container);
                                        // Bring the container into view so any layout-dependent
                                        // measurements settle, then attempt a one-pass capture.
                                        try { container.scrollIntoView({ block: 'start' }); } catch(e) {}
                                        await sleep(100);
                                        var savedHosts = new Set();
                                        await captureOnce(container, pageLabel, savedHosts);
                                        // If something is still missing (rare — only the lazy
                                        // single-tile path), fall back to the scroll loop with
                                        // a short total budget.
                                        if (savedHosts.size < expected) {
                                            var contRect = container.getBoundingClientRect();
                                            var baseY = (window.scrollY || 0) + contRect.top;
                                            var contH = container.offsetHeight || contRect.height || 2100;
                                            var step = Math.max(400, Math.floor((window.innerHeight || 4096) * 0.5));
                                            for (var sy = 0; sy <= contH + step && savedHosts.size < expected; sy += step) {
                                                window.scrollTo(0, baseY + sy - 100);
                                                await sleep(400);
                                                await captureOnce(container, pageLabel, savedHosts);
                                            }
                                        }
                                        window.__jlog('[japscan] ' + pageLabel + ' completed: ' + savedHosts.size + '/' + expected + ' tile(s)');
                                        try {
                                            if (container.parentNode) container.parentNode.removeChild(container);
                                        } catch(e) {}
                                        dImgContainers[i] = null;
                                    }
                                    window.__jlog('[japscan] webtoon done, ' + tilesEmitted + ' tile(s) saved across ' + total + ' d-img containers');
                                    try { window.$$interfaceName.passDone(); } catch(e) {}
                                    return;
                                }

                                // Kotlin committed to the webtoon hook set before this page loaded,
                                // and the paginated reader refuses to deliver its payload with those
                                // hooks installed — so there is nothing useful to do from here.
                                // Report and bail instead of grinding through a driver that cannot
                                // work; if this ever fires, the fix is to reload the WebView with
                                // the paginated hooks, not a third capture technique.
                                window.__jlog('[japscan] MISMATCH: url hinted webtoon but the DOM mounted the paginated reader — giving up');
                                try { window.$$interfaceName.passDone(); } catch(e) {}
                            })();
""".trimIndent()
