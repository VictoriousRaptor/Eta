package io.github.mangi.eta.ui.links

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import org.robolectric.Shadows.shadowOf
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AppLinkOpenerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun webpagesOpenInEtaByDefault() {
        for (url in listOf("https://example.com/path?q=1#part", "http://example.com", "HTTPS://example.com")) {
            val intent = AppLinkOpener.intentFor(context, url, openInApp = true)
            assertEquals(ComponentName(context, LinkWebViewActivity::class.java), intent.component)
            assertEquals(Uri.parse(url), intent.data)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test fun disablingSwitchRestoresExternalBrowser() {
        val intent = AppLinkOpener.intentFor(context, "https://example.com", openInApp = false)
        assertNull(intent.component)
        assertEquals(Intent.ACTION_VIEW, intent.action)
    }

    @Test fun nonWebLinksKeepSystemRouting() {
        for (url in listOf("mailto:hello@example.com", "tel:123", "geo:0,0", "customapp://page")) {
            val intent = AppLinkOpener.intentFor(context, url, openInApp = true)
            assertNull(intent.component)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(Uri.parse(url), intent.data)
        }
    }

    @Test fun preferenceDefaultsOnAndBelongsToLocalSettings() {
        assertEquals(true, Prefs.Keys.BOOLEAN_DEFAULTS[Prefs.Keys.OPEN_LINKS_IN_APP])
        assertTrue(Prefs.Keys.OPEN_LINKS_IN_APP in Prefs.Keys.LOCAL_AGENT_KEYS)
    }

    @Test fun browserActivityIsPrivate() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, LinkWebViewActivity::class.java), 0)
        assertFalse(info.exported)
    }

    @Test fun onlyHierarchicalHttpUrlsWithHostsCanEnterWebView() {
        for (url in listOf("https://example.com", "http://localhost:8000")) {
            assertTrue(AppLinkOpener.isWebUri(Uri.parse(url)))
        }
        for (url in listOf("https:example.com", "https:///", "file:///data/private", "content://provider/file", "javascript:alert(1)", "data:text/html,test")) {
            assertFalse(url, AppLinkOpener.isWebUri(Uri.parse(url)))
        }
    }

    @Test fun composeUriHandlerUsesTheSameInAppRouting() {
        AppUriHandler(context).openUri("https://example.com/from-markdown")
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(ComponentName(context, LinkWebViewActivity::class.java), intent.component)
        assertEquals(Uri.parse("https://example.com/from-markdown"), intent.data)
    }
    @Test fun changingPersistedSwitchAffectsTheNextClick() {
        Prefs.initLocal(context)
        val prefs = requireNotNull(Prefs.localAgentPreferences())
        try {
            prefs.edit().putBoolean(Prefs.Keys.OPEN_LINKS_IN_APP, false).commit()
            assertNull(AppLinkOpener.intentFor(context, "https://example.com").component)
            prefs.edit().putBoolean(Prefs.Keys.OPEN_LINKS_IN_APP, true).commit()
            assertNotNull(AppLinkOpener.intentFor(context, "https://example.com").component)
        } finally {
            prefs.edit().remove(Prefs.Keys.OPEN_LINKS_IN_APP).commit()
        }
    }

    @Test fun actualWebViewClientKeepsRedirectsInsideAndGatesExternalActions() {
        val view = WebView(context)
        val client = LinkWebViewClient {}
        try {
            assertFalse(client.shouldOverrideUrlLoading(view, request("https://example.com/redirect", gesture = false)))
            assertTrue(client.shouldOverrideUrlLoading(view, request("mailto:hello@example.com", gesture = false)))
            assertTrue(client.shouldOverrideUrlLoading(view, request("file:///data/private", gesture = true)))
            assertNull(shadowOf(context).nextStartedActivity)
            assertTrue(client.shouldOverrideUrlLoading(view, request("mailto:hello@example.com", gesture = true)))
            assertEquals(Uri.parse("mailto:hello@example.com"), shadowOf(context).nextStartedActivity.data)
        } finally {
            view.destroy()
        }
    }

    private fun request(url: String, gesture: Boolean) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
    @Test fun webpagesCannotLaunchAppsWithoutMainFrameUserGesture() {
        val mail = Uri.parse("mailto:hello@example.com")
        assertTrue(LinkWebViewClient.canOpenExternal(mail, isMainFrame = true, hasGesture = true))
        assertFalse(LinkWebViewClient.canOpenExternal(mail, isMainFrame = true, hasGesture = false))
        assertFalse(LinkWebViewClient.canOpenExternal(mail, isMainFrame = false, hasGesture = true))
        for (url in listOf("intent://app#Intent;scheme=app;end", "file:///data/private", "content://provider/file", "javascript:alert(1)")) {
            assertFalse(LinkWebViewClient.canOpenExternal(Uri.parse(url), isMainFrame = true, hasGesture = true))
        }
    }
}
