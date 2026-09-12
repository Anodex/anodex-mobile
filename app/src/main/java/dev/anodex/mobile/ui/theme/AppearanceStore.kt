package dev.anodex.mobile.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.anodex.mobile.ui.screens.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore("appearance")

/**
 * Whether this phone follows its own dark-mode setting, or is pinned.
 *
 * Deliberately phone-local, and the one setting here that is. Everything else in
 * Settings is the computer's and moves for whoever is at the desk too — the active
 * personality, the loaded model, the project. This is about the screen in your hand
 * at midnight, which has nothing to do with the machine.
 *
 * Its own DataStore rather than a key in the pairing one: that store holds the device
 * key, is cleared on unpair, and losing a theme preference because somebody re-paired
 * would be a small confusing bug for no reason.
 */
class AppearanceStore(private val context: Context) {

    val themeMode: Flow<ThemeMode> = context.appearanceDataStore.data.map { prefs ->
        // An unreadable or unknown value falls back to following the system, which is
        // what an app that has never been told anything should do.
        prefs[KEY_THEME]
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appearanceDataStore.edit { it[KEY_THEME] = mode.name }
    }

    /**
     * How large the interface is set, and in what face.
     *
     * Phone-local for the same reason the theme is: this is about the screen in your
     * hand and how far away you are holding it, which has nothing to do with the
     * machine at the other end.
     */
    val fontScale: Flow<FontScale> = context.appearanceDataStore.data.map { prefs ->
        prefs[KEY_FONT_SCALE]
            ?.let { stored -> FontScale.entries.firstOrNull { it.name == stored } }
            ?: FontScale.MEDIUM
    }

    suspend fun setFontScale(scale: FontScale) {
        context.appearanceDataStore.edit { it[KEY_FONT_SCALE] = scale.name }
    }

    val uiFont: Flow<UiFont> = context.appearanceDataStore.data.map { prefs ->
        prefs[KEY_FONT]
            ?.let { stored -> UiFont.entries.firstOrNull { it.name == stored } }
            ?: UiFont.SYSTEM
    }

    suspend fun setUiFont(font: UiFont) {
        context.appearanceDataStore.edit { it[KEY_FONT] = font.name }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        val KEY_FONT = stringPreferencesKey("ui_font")
    }
}
