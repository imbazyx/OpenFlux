package io.openflux.android.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.openflux.desktop.ui.BrowserPage
import io.openflux.desktop.ui.BrowserViews
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One page of the built-in browser: a WebView the UI shows with
 * [WebBrowserViews]. It is created the first time it is shown and kept until
 * [close], so hiding the dialog does not lose the page. [proxy] ("host:port")
 * sends it through the tunnel, for a check the exit node must pass from its
 * own address.
 */
class WebPage(
    private val startUrl: String,
    private val proxy: String = "",
    private val scripts: Boolean = false,
) : BrowserPage {
    @Volatile var url: String = startUrl
        private set
    @Volatile var loading = true
        private set

    /**
     * Why the last load failed, or null while it is fine.
     *
     * The screen has something to show and the captcha wait has a reason to
     * stop; before this a failed page was indistinguishable from a slow one.
     */
    @Volatile var error: String? = null
        private set
    @Volatile var closed = false
        private set

    private var view: WebView? = null
    private var proxyOverridden = false
    private val results = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val ids = AtomicLong()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    internal fun view(context: Context): WebView {
        view?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val web = WebView(context)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The UA the core fetches the document with: a check's pass may be bound to it.
            userAgentString = USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        if (scripts) web.addJavascriptInterface(Results(), BRIDGE)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                this@WebPage.url = url
                loading = true
                error = null
            }

            override fun onPageFinished(view: WebView, url: String) {
                this@WebPage.url = url
                loading = false
            }

            /**
             * A page that fails used to leave [loading] true forever.
             *
             * There was no error callback at all, so a refused connection left
             * a blank screen with a spinner, and the captcha wait - a loop that
             * only ends when the page stops loading - never ended either. A
             * five-second failure turned into a hang with nothing to look at.
             */
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                failure: WebResourceError,
            ) {
                // Only the main document: a failed image or tracker must not
                // be reported as the page failing to open.
                if (!request.isForMainFrame) return
                fail(describe(failure))
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                fail("сервер ответил ${errorResponse.statusCode}")
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // The default is to cancel, which is right: continuing would
                // accept a certificate the core never vouched for. It used to
                // do so silently, so TLS interception looked like a dead page.
                handler.cancel()
                fail("ошибка сертификата: ${error.primaryError}")
            }
        }
        view = web
        // The proxy override is process-wide, not per-WebView, so a page that
        // set one and did not clear it sends every later page in the app
        // through a listener that no longer exists. The address comes from a
        // port chosen at random and a fresh one is minted on every reconnect,
        // while the old listener is closed - so after the first reconnect this
        // is a closed socket and every page fails with ERR_PROXY_CONNECTION_FAILED.
        //
        // Clearing here, before anything is loaded, makes each page decide for
        // itself instead of inheriting whatever the last one happened to leave.
        ProxyController.getInstance().clearProxyOverride(Runnable::run) {}
        if (proxy.isEmpty()) {
            web.loadUrl(startUrl)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyOverridden = true
            val config = ProxyConfig.Builder().addProxyRule(proxy).build()
            ProxyController.getInstance().setProxyOverride(config, Runnable::run) { if (!closed) web.loadUrl(startUrl) }
        } else {
            web.loadData(
                "<p style='font:16px sans-serif;padding:16px'>WebView не умеет работать через прокси: обновите «Android System WebView» в Google Play.</p>",
                "text/html; charset=utf-8", "utf-8",
            )
        }
        return web
    }

    private fun fail(reason: String) {
        error = reason
        // Cleared here as well as in the callbacks: onReceivedError and
        // onPageFinished can both fire, and a page that never finishes is the
        // case that matters - the captcha wait is a loop over !loading.
        loading = false
    }

    private fun describe(failure: WebResourceError): String =
        "не удалось открыть страницу" +
            (failure.description?.toString()?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

    /** Runs [expression] (it may return a Promise) on the page; its value as a string. */
    suspend fun evaluate(expression: String, timeoutMs: Long = 90_000): String {
        check(scripts) { "This page does not run scripts" }
        val id = ids.incrementAndGet().toString()
        val result = CompletableDeferred<String>().also { results[id] = it }
        try {
            withContext(Dispatchers.Main) {
                val web = view ?: throw IllegalStateException("Страница ещё не открыта")
                web.evaluateJavascript(
                    """
                    (async () => {
                      let v;
                      try { v = await ($expression); } catch (e) { v = JSON.stringify({state: 'fail', error: String((e && e.message) || e)}); }
                      window.$BRIDGE.result('$id', typeof v === 'string' ? v : JSON.stringify(v));
                    })();
                    """.trimIndent(),
                    null,
                )
            }
            return withTimeout(timeoutMs) { result.await() }
        } finally {
            results.remove(id)
        }
    }

    /** The Cookie header the page's cookie jar sends to [url]. */
    fun cookieHeader(url: String): String = CookieManager.getInstance().getCookie(url).orEmpty()

    fun close() {
        if (closed) return
        closed = true
        results.values.forEach { it.cancel() }
        val web = view
        view = null
        // Handler(Looper.getMainLooper()), not View.post. A WebView that was
        // created and then closed before its first layout pass has no
        // AttachInfo, so View.post queues into the view's run-queue and only
        // ever drains from dispatchAttachedToWindow - which for a disposed page
        // is never. Both the destroy() and the clear below sat in that queue
        // doing nothing, and the override is process-wide: it pointed every
        // later WebView in the app at a listener that had died with the run.
        // That is what made pages stop opening.
        Handler(Looper.getMainLooper()).post {
            (web?.parent as? ViewGroup)?.removeView(web)
            web?.destroy()
            // Unconditionally, not only when this page set one: the override
            // is shared state and this page may be inheriting it.
            ProxyController.getInstance().clearProxyOverride(Runnable::run) {}
        }
    }

    private inner class Results {
        @JavascriptInterface
        fun result(id: String, value: String) {
            results[id]?.complete(value)
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:153.0) Gecko/20100101 Firefox/153.0"
        private const val BRIDGE = "OpenFluxBridge"

        /** Every cookie of the built-in browser, the Yandex sign-in among them. */
        fun clearCookies() {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
    }
}

/** Shows [WebPage]s inside the Compose UI. */
object WebBrowserViews : BrowserViews {
    @Composable
    override fun Page(page: BrowserPage, modifier: Modifier) {
        val web = page as? WebPage ?: return
        key(web) {
            AndroidView(factory = { context -> web.view(context) }, modifier = modifier)
        }
    }
}
