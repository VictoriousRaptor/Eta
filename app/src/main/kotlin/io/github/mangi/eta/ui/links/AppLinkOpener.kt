package io.github.mangi.eta.ui.links

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
        val uri = Uri.parse(url)
        return (if (openInApp && isWebUri(uri)) {
            Intent(context, LinkWebViewActivity::class.java).setData(uri)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

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
