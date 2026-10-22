package io.github.mangi.eta.ui.components

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.mangi.eta.config.Prefs

@Composable
internal fun rememberSteeringEnabled(): Boolean {
    val preferences = remember { Prefs.localAgentPreferences() }
    var enabled by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.AGENT_STEER_ENABLED)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_STEER_ENABLED) enabled = Prefs.isEnabled(key)
        }
        preferences?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences?.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}
