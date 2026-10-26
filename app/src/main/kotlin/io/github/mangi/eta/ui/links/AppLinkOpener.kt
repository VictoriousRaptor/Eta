package io.github.mangi.eta.ui.links

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.compose.ui.platform.UriHandler
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs

/** Routes user-clicked links independently of the Agent's automated browser session. */
internal object AppLinkOpener {
    fun isWebUri(uri: Uri): Boolean =
        (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            uri.isHierarchical && !uri.host.isNullOrBlank()

    fun intentFor(
        context: Context,
        url: String,
        openInApp: Boolean = Prefs.isEnabled(Prefs.Keys.OPEN_LINKS_IN_APP),
    ): Intent {
        val parsedUri = Uri.parse(url)
        val isWebLink = isWebUri(parsedUri)
        val uri = if (isWebLink) parsedUri.normalizeScheme() else parsedUri
        val browserPackage = if (openInApp && isWebLink) customTabsPackage(context) else null
        return (if (browserPackage != null) {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setSendToExternalDefaultHandlerEnabled(false)
                .build().intent
                .setPackage(browserPackage)
                .setData(uri)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun customTabsPackage(context: Context): String? =
        CustomTabsClient.getPackageName(context, null)
            ?: context.packageManager.queryIntentServices(
                Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION),
                PackageManager.ResolveInfoFlags.of(0),
            ).firstOrNull()?.serviceInfo?.packageName

    fun open(context: Context, url: String, openInApp: Boolean = Prefs.isEnabled(Prefs.Keys.OPEN_LINKS_IN_APP)) {
        try {
            context.startActivity(intentFor(context, url, openInApp))
        } catch (_: ActivityNotFoundException) {
            showOpenError(context)
        } catch (_: SecurityException) {
            showOpenError(context)
        }
    }

    private fun showOpenError(context: Context) {
        Toast.makeText(context, R.string.browser_no_external_app, Toast.LENGTH_SHORT).show()
    }
}

internal class AppUriHandler(private val context: Context) : UriHandler {
    override fun openUri(uri: String) = AppLinkOpener.open(context, uri)
}
