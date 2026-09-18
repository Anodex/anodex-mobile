package dev.anodex.mobile.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        // Nothing stored, or a value this version doesn't know, reads as dark. Anodex
        // is a dark app first — the desktop is, and a phone on a light system setting
        // opened into a pale version that looked like a different product. Anyone
        // who picked a theme, System included, keeps it: that choice is stored.
        prefs[KEY_THEME]
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: DEFAULT_THEME_MODE
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

    /**
     * Whether to hold the screen on while a reply is arriving.
     *
     * On by default, because the alternative is what it replaced: start a run from
     * the sofa, watch it work, and the screen sleeps in the middle of it. The cost is
     * bounded — it applies only while tokens are actually arriving, not while the app
     * is merely open.
     */
    val keepAwake: Flow<Boolean> = context.appearanceDataStore.data.map { prefs ->
        prefs[KEY_KEEP_AWAKE] ?: true
    }

    suspend fun setKeepAwake(enabled: Boolean) {
        context.appearanceDataStore.edit { it[KEY_KEEP_AWAKE] = enabled }
    }

    /**
     * Whether this app animates, independently of the phone.
     *
     * Three-way rather than a switch. The phone's own setting is the right default and
     * most people never touch either — but "the system animates and this app should
     * not" is a real preference, and a two-state toggle cannot express it without
     * either ignoring the system or overriding it permanently.
     */
    val motion: Flow<MotionPreference> = context.appearanceDataStore.data.map { prefs ->
        prefs[KEY_MOTION]
            ?.let { stored -> MotionPreference.entries.firstOrNull { it.name == stored } }
            ?: MotionPreference.SYSTEM
    }

    suspend fun setMotion(preference: MotionPreference) {
        context.appearanceDataStore.edit { it[KEY_MOTION] = preference.name }
    }

    /**
     * Whether the phone taps back.
     *
     * On by default. The moment it matters is approving a tool call from a pocket,
     * where a confirmation you can feel is worth more than one you have to look at.
     */
    val haptics: Flow<Boolean> = context.appearanceDataStore.data.map { prefs ->
        prefs[KEY_HAPTICS] ?: true
    }

    suspend fun setHaptics(enabled: Boolean) {
        context.appearanceDataStore.edit { it[KEY_HAPTICS] = enabled }
    }

    /**
     * Whether to take the live token stream on a metered connection.
     *
     * Off by default, and that default is the point. Since the computer started
     * broadcasting a running turn, a phone on mobile data receives every token of
     * every turn — including for a conversation it does not have open. It costs
     * nothing to decline: the conversation still arrives whole when the turn is
     * saved, a moment later rather than a word at a time.
     *
     * Wi-Fi is never affected. This is about connections charged by the byte.
     */
    val streamOnMetered: Flow<Boolean> = context.appearanceDataStore.data.map { prefs ->
        prefs[KEY_STREAM_ON_METERED] ?: false
    }

    suspend fun setStreamOnMetered(enabled: Boolean) {
        context.appearanceDataStore.edit { it[KEY_STREAM_ON_METERED] = enabled }
    }

    /**
     * Whether a message's pictures load without being asked for.
     *
     * On by default, which is a deliberate choice and worth stating. Fetching a
     * remote image tells the sender the message was opened, roughly when, and
     * from which network -- so holding them back is the privacy-preserving
     * default and was what this app did first.
     *
     * It was also, in practice, a mailbox that looked broken: a newsletter is
     * mostly pictures, and every one of them came through as a blank box with a
     * button underneath. The owner asked for it to read like the mail client they
     * already use, which loads them.
     *
     * Two things make the default defensible. The computer fetches them, not the
     * phone, so a sender learns the address of a machine that already holds the
     * mailbox rather than where the phone is. And this switch turns it off in one
     * tap, which puts the choice in front of whoever wants it rather than in
     * front of everybody on every message.
     */
    val loadImages: Flow<Boolean> = context.appearanceDataStore.data.map { prefs ->
        prefs[KEY_LOAD_IMAGES] ?: true
    }

    suspend fun setLoadImages(enabled: Boolean) {
        context.appearanceDataStore.edit { it[KEY_LOAD_IMAGES] = enabled }
    }

    private companion object {
        val KEY_LOAD_IMAGES = booleanPreferencesKey("load_remote_images")
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_STREAM_ON_METERED = booleanPreferencesKey("stream_on_metered")
        val KEY_FONT_SCALE = stringPreferencesKey("font_scale")
        val KEY_FONT = stringPreferencesKey("ui_font")
        val KEY_KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val KEY_MOTION = stringPreferencesKey("motion")
        val KEY_HAPTICS = booleanPreferencesKey("haptics")
    }
}

/** The theme before anybody has chosen one. */
val DEFAULT_THEME_MODE = ThemeMode.DARK
