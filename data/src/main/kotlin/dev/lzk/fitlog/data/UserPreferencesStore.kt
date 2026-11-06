package dev.lzk.fitlog.data

import android.content.Context
import androidx.datastore.preferences.core.*
import dev.lzk.fitlog.domain.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map

class UserPreferencesStore(context: Context, scope: CoroutineScope, filename: String = "fitlog.preferences_pb") : PreferencesRepository, PreparationRepository {
    private val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(context.filesDir, filename) })
    private object Keys {
        val preparation = stringPreferencesKey("prepared_exercises")
        val equipment = stringSetPreferencesKey("equipment")
        val muscles = stringSetPreferencesKey("muscles")
        val split = stringPreferencesKey("split")
        val minutes = intPreferencesKey("minutes")
        val unit = stringPreferencesKey("unit")
        val step = longPreferencesKey("step_grams")
        val restEnabled = booleanPreferencesKey("rest_enabled")
        val rest = intPreferencesKey("rest_seconds")
        val onboarded = booleanPreferencesKey("onboarded")
        val theme = stringPreferencesKey("theme")
        val haptics = booleanPreferencesKey("haptics_enabled")
    }
    override val preparedExercises = store.data.map { values ->
        values[Keys.preparation]?.let { text ->
            runCatching { org.json.JSONArray(text).let { array -> (0 until array.length()).map { array.getString(it) } } }.getOrDefault(emptyList())
        }.orEmpty()
    }
    override suspend fun savePreparedExercises(ids: List<String>) {
        require(ids.size <= 30 && ids.distinct().size == ids.size)
        store.edit { it[Keys.preparation] = org.json.JSONArray(ids).toString() }
    }
    override val preferences = store.data.map { values ->
        UserPreferences(
            equipment = values[Keys.equipment].orEmpty().mapNotNull { runCatching { EquipmentType.valueOf(it) }.getOrNull() }.toSet(),
            muscles = values[Keys.muscles].orEmpty().mapNotNull { runCatching { Muscle.valueOf(it) }.getOrNull() }.toSet(),
            split = values[Keys.split]?.let { runCatching { SplitType.valueOf(it) }.getOrNull() } ?: SplitType.FREE,
            workoutMinutes = (values[Keys.minutes] ?: 45).coerceIn(5, 360),
            weightUnit = values[Keys.unit]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: WeightUnit.KG,
            weightStepGrams = (values[Keys.step] ?: 2500).coerceAtLeast(1),
            restEnabled = values[Keys.restEnabled] ?: true,
            restSeconds = (values[Keys.rest] ?: 90).coerceIn(0, 3600),
            onboardingCompleted = values[Keys.onboarded] ?: false,
            theme = values[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.TIME,
            hapticsEnabled = values[Keys.haptics] ?: true,
        )
    }

    override suspend fun save(preferences: UserPreferences) {
        preferences.validate()
        store.edit { values ->
            values[Keys.equipment] = preferences.equipment.map { it.name }.toSet()
            values[Keys.muscles] = preferences.muscles.map { it.name }.toSet()
            values[Keys.split] = preferences.split.name
            values[Keys.minutes] = preferences.workoutMinutes
            values[Keys.unit] = preferences.weightUnit.name
            values[Keys.step] = preferences.weightStepGrams
            values[Keys.restEnabled] = preferences.restEnabled
            values[Keys.rest] = preferences.restSeconds
            values[Keys.onboarded] = preferences.onboardingCompleted
            values[Keys.theme] = preferences.theme.name
            values[Keys.haptics] = preferences.hapticsEnabled
        }
    }
}
