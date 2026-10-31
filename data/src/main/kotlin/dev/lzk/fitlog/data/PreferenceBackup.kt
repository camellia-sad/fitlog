package dev.lzk.fitlog.data

import dev.lzk.fitlog.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal fun UserPreferences.backupJson() = JSONObject()
    .put("equipment", JSONArray(equipment.map {it.name})).put("muscles", JSONArray(muscles.map {it.name}))
    .put("split",split.name).put("workoutMinutes",workoutMinutes).put("weightUnit",weightUnit.name)
    .put("weightStepGrams",weightStepGrams).put("restEnabled",restEnabled).put("restSeconds",restSeconds)
    .put("onboardingCompleted",onboardingCompleted).put("theme",theme.name)

internal fun JSONObject.backupPreferences() = UserPreferences(
    getJSONArray("equipment").strings().map {EquipmentType.valueOf(it)}.toSet(),
    getJSONArray("muscles").strings().map {Muscle.valueOf(it)}.toSet(),
    SplitType.valueOf(getString("split")),getInt("workoutMinutes"),WeightUnit.valueOf(getString("weightUnit")),
    getLong("weightStepGrams"),getBoolean("restEnabled"),getInt("restSeconds"),
    getBoolean("onboardingCompleted"),ThemeMode.valueOf(getString("theme")),
).also {it.validate() }
