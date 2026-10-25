package io.github.mangi.eta.ui.links

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.MiuixScaffold
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text

/** Owns a user browsing view; never navigates or attaches the Agent's shared WebView. */
class LinkWebViewActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var currentUrl by mutableStateOf("")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null || !AppLinkOpener.isWebUri(uri)) {
            finish()
            return
        }
        enableEdgeToEdge()
        currentUrl = uri.toString()
        val browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = WebChromeClient()
            webViewClient = LinkWebViewClient { currentUrl = it }
        }
        webView = browser
        val restored = savedInstanceState?.getBundle(WEB_STATE)?.let(browser::restoreState)
        if (restored == null) browser.loadUrl(currentUrl)
        currentUrl = browser.url ?: currentUrl

        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance = appearance, applyInterfaceScale = true) {
                BackHandler { goBackOrFinish() }
                MiuixScaffold(
                    title = Uri.parse(currentUrl).host ?: stringResource(R.string.link_browser_title),
                    onBack = ::goBackOrFinish,
                    actions = {
                        IconButton(onClick = { browser.reload() }) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.browser_refresh))
                        }
                        IconButton(onClick = { AppLinkOpener.open(this@LinkWebViewActivity, currentUrl, openInApp = false) }) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.browser_open_external))
                        }
                    },
                ) { padding, _, _ ->
                    Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
                        Text(
                            text = currentUrl,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        AndroidView(factory = { browser }, modifier = Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }

    private fun goBackOrFinish() {
        val browser = webView
        if (browser != null && browser.canGoBack()) browser.goBack() else finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.let { browser ->
            outState.putBundle(WEB_STATE, Bundle().also { browser.saveState(it) })
        }
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        webView?.let { browser ->
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.stopLoading()
            browser.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private companion object {
        const val WEB_STATE = "link_web_state"
    }
}

internal class LinkWebViewClient(private val onUrlChanged: (String) -> Unit) : WebViewClient() {
    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        url?.let(onUrlChanged)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        url?.let(onUrlChanged)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (AppLinkOpener.isWebUri(request.url)) return false
        if (canOpenExternal(request.url, request.isForMainFrame, request.hasGesture())) {
            AppLinkOpener.open(view.context, request.url.toString(), openInApp = false)
        }
        return true
    }

    companion object {
        // Only direct, top-level taps may invoke these system actions; redirects and embedded frames cannot.
        fun canOpenExternal(uri: Uri, isMainFrame: Boolean, hasGesture: Boolean): Boolean =
            isMainFrame && hasGesture && uri.scheme?.lowercase() in setOf("mailto", "tel", "sms", "smsto", "geo")
    }
}
