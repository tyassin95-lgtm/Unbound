package com.unbound.rpg.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unbound.core.model.ImageMode
import com.unbound.core.model.NarrationLength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "unbound_settings")

/**
 * Device-level preferences.
 *
 * Note what is *not* here: the API key. Preferences are stored in plaintext on disk and are
 * eligible for backup; the credential lives in the Keystore-backed store instead. Only the
 * player's *choices* live here.
 */
class AppSettings(context: Context) {

    private val store = context.applicationContext.dataStore

    val state: Flow<SettingsState> = store.data.map { prefs -> prefs.toState() }

    private fun Preferences.toState() = SettingsState(
        defaultTextModelId = this[KEY_TEXT_MODEL],
        defaultImageModelId = this[KEY_IMAGE_MODEL],
        imageMode = this[KEY_IMAGE_MODE]?.let { runCatching { ImageMode.valueOf(it) }.getOrNull() } ?: ImageMode.ON_DEMAND,
        narrationLength = this[KEY_NARRATION]?.let { runCatching { NarrationLength.valueOf(it) }.getOrNull() } ?: NarrationLength.NORMAL,
        suggestedActions = this[KEY_SUGGESTIONS] ?: true,
        reducedMotion = this[KEY_REDUCED_MOTION] ?: false,
        developerMode = this[KEY_DEVELOPER] ?: false,
        personalLimits = this[KEY_LIMITS]?.lines()?.filter { it.isNotBlank() } ?: emptyList(),
        onboardingComplete = this[KEY_ONBOARDED] ?: false,
    )

    suspend fun setTextModel(id: String?) = put(KEY_TEXT_MODEL, id)
    suspend fun setImageModel(id: String?) = put(KEY_IMAGE_MODEL, id)
    suspend fun setImageMode(mode: ImageMode) = put(KEY_IMAGE_MODE, mode.name)
    suspend fun setNarrationLength(length: NarrationLength) = put(KEY_NARRATION, length.name)
    suspend fun setSuggestedActions(enabled: Boolean) { store.edit { it[KEY_SUGGESTIONS] = enabled } }
    suspend fun setReducedMotion(enabled: Boolean) { store.edit { it[KEY_REDUCED_MOTION] = enabled } }
    suspend fun setDeveloperMode(enabled: Boolean) { store.edit { it[KEY_DEVELOPER] = enabled } }
    suspend fun setOnboardingComplete(done: Boolean) { store.edit { it[KEY_ONBOARDED] = done } }
    suspend fun setPersonalLimits(limits: List<String>) = put(KEY_LIMITS, limits.joinToString("\n"))

    private suspend fun put(key: Preferences.Key<String>, value: String?) {
        store.edit { prefs -> if (value == null) prefs.remove(key) else prefs[key] = value }
    }

    private companion object {
        val KEY_TEXT_MODEL = stringPreferencesKey("text_model")
        val KEY_IMAGE_MODEL = stringPreferencesKey("image_model")
        val KEY_IMAGE_MODE = stringPreferencesKey("image_mode")
        val KEY_NARRATION = stringPreferencesKey("narration_length")
        val KEY_SUGGESTIONS = booleanPreferencesKey("suggested_actions")
        val KEY_REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val KEY_DEVELOPER = booleanPreferencesKey("developer_mode")
        val KEY_LIMITS = stringPreferencesKey("personal_limits")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarding_complete")
    }
}

data class SettingsState(
    val defaultTextModelId: String? = null,
    val defaultImageModelId: String? = null,
    val imageMode: ImageMode = ImageMode.ON_DEMAND,
    val narrationLength: NarrationLength = NarrationLength.NORMAL,
    val suggestedActions: Boolean = true,
    val reducedMotion: Boolean = false,
    val developerMode: Boolean = false,
    val personalLimits: List<String> = emptyList(),
    val onboardingComplete: Boolean = false,
)
