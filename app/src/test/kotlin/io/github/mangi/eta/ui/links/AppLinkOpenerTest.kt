package io.github.mangi.eta.ui.links

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
class AppLinkOpenerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun webpagesUseBrowserProvidedCustomTabs() {
        registerBrowser("browser.supported")
        for (url in listOf("https://example.com/path?q=1#part", "http://example.com", "HTTPS://example.com")) {
            val intent = requireNotNull(AppLinkOpener.intentFor(context, url, openInApp = true))
            assertEquals("browser.supported", intent.`package`)
            assertNull(intent.component)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(Uri.parse(url).normalizeScheme(), intent.data)
            assertTrue(intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
            assertEquals(CustomTabsIntent.SHOW_PAGE_TITLE, intent.getIntExtra(CustomTabsIntent.EXTRA_TITLE_VISIBILITY_STATE, 0))
            assertFalse(intent.getBooleanExtra(CustomTabsIntent.EXTRA_SEND_TO_EXTERNAL_DEFAULT_HANDLER, true))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test fun supportedDefaultBrowserIsPreferredOverOtherProviders() {
        registerBrowser("browser.other")
        registerBrowser("browser.default")
        registerDefaultBrowser("browser.default")
        assertEquals("browser.default", AppLinkOpener.intentFor(context, "https://example.com", true).`package`)
    }

    @Test fun unsupportedDefaultBrowserUsesAnotherCustomTabsProvider() {
        registerDefaultBrowser("browser.unsupported")
        registerBrowser("browser.supported")
        assertEquals("browser.supported", AppLinkOpener.intentFor(context, "https://example.com", true).`package`)
    }

    @Test fun noProviderDirectlyUsesTheSystemBrowser() {
        registerDefaultBrowser("browser.unsupported")
        AppLinkOpener.open(context, "https://example.com", true)
        val intent = requireNotNull(shadowOf(context).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse("https://example.com"), intent.data)
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertFalse(intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }

    @Test fun mixedCaseWebLinksMatchBrowserFilters() {
        val filter = IntentFilter(Intent.ACTION_VIEW).apply {
            addDataScheme("http")
            addDataScheme("https")
        }
        for (providerAvailable in listOf(false, true)) {
            if (providerAvailable) registerBrowser("browser.supported")
            for (openInApp in listOf(false, true)) {
                val intent = AppLinkOpener.intentFor(context, "HTTPS://example.com/Case?Query=UPPER", openInApp)
                assertTrue(filter.matchData(intent.type, intent.scheme, intent.data) >= 0)
                assertEquals("https://example.com/Case?Query=UPPER", intent.data.toString())
            }
        }
    }

    @Test fun activityContextKeepsTheCustomTabInTheCallingTask() {
        registerBrowser("browser.supported")
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val intent = requireNotNull(AppLinkOpener.intentFor(controller.get(), "https://example.com", true))
            assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun disablingSwitchRestoresExternalBrowserEvenWithoutACustomTabsProvider() {
        val intent = requireNotNull(AppLinkOpener.intentFor(context, "https://example.com", false))
        assertNull(intent.component)
        assertNull(intent.`package`)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertFalse(intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
    }

    @Test fun nonWebLinksKeepSystemRouting() {
        for (url in listOf("mailto:hello@example.com", "tel:123", "geo:0,0", "customapp://page")) {
            val intent = requireNotNull(AppLinkOpener.intentFor(context, url, true))
            assertNull(intent.component)
            assertNull(intent.`package`)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(Uri.parse(url), intent.data)
            assertFalse(intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        }
    }

    @Test fun preferenceDefaultsOnAndKeepsTheExistingLocalKey() {
        assertEquals("open_links_in_app", Prefs.Keys.OPEN_LINKS_IN_APP)
        assertEquals(true, Prefs.Keys.BOOLEAN_DEFAULTS[Prefs.Keys.OPEN_LINKS_IN_APP])
        assertTrue(Prefs.Keys.OPEN_LINKS_IN_APP in Prefs.Keys.LOCAL_AGENT_KEYS)
    }

    @Test fun oldWebViewActivityIsNoLongerRegistered() {
        assertThrows(PackageManager.NameNotFoundException::class.java) {
            context.packageManager.getActivityInfo(ComponentName(context.packageName, "io.github.mangi.eta.ui.links.LinkWebViewActivity"), 0)
        }
    }

    @Test fun onlyHierarchicalHttpUrlsWithHostsAreWebLinks() {
        for (url in listOf("https://example.com", "http://localhost:8000")) {
            assertTrue(AppLinkOpener.isWebUri(Uri.parse(url)))
        }
        for (url in listOf("https:example.com", "https:///", "file:///data/private", "content://provider/file", "javascript:alert(1)", "data:text/html,test")) {
            assertFalse(url, AppLinkOpener.isWebUri(Uri.parse(url)))
        }
    }

    @Test fun composeUriHandlerUsesTheSameCustomTabsRouting() {
        registerBrowser("browser.supported")
        AppUriHandler(context).openUri("https://example.com/from-markdown")
        val intent = shadowOf(context).nextStartedActivity
        assertEquals("browser.supported", intent.`package`)
        assertTrue(intent.hasExtra(CustomTabsIntent.EXTRA_SESSION))
        assertEquals(Uri.parse("https://example.com/from-markdown"), intent.data)
    }

    @Test fun changingPersistedSwitchAffectsTheNextClick() {
        registerBrowser("browser.supported")
        Prefs.initLocal(context)
        val prefs = requireNotNull(Prefs.localAgentPreferences())
        try {
            prefs.edit().putBoolean(Prefs.Keys.OPEN_LINKS_IN_APP, false).commit()
            assertNull(AppLinkOpener.intentFor(context, "https://example.com").`package`)
            prefs.edit().putBoolean(Prefs.Keys.OPEN_LINKS_IN_APP, true).commit()
            assertEquals("browser.supported", AppLinkOpener.intentFor(context, "https://example.com").`package`)
        } finally {
            prefs.edit().remove(Prefs.Keys.OPEN_LINKS_IN_APP).commit()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBrowser(packageName: String) {
        val service = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                this.packageName = packageName
                name = "$packageName.CustomTabsService"
                exported = true
                enabled = true
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName; enabled = true }
            }
        }
        val pm = shadowOf(context.packageManager)
        pm.addResolveInfoForIntent(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION), service)
        pm.addResolveInfoForIntent(Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).setPackage(packageName), service)
    }

    @Suppress("DEPRECATION")
    private fun registerDefaultBrowser(packageName: String) {
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse("http://")), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.BrowserActivity"
                exported = true
                enabled = true
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName; enabled = true }
            }
        })
    }
}
