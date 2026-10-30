package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.withTransaction
import dev.lzk.fitlog.domain.EquipmentType
import dev.lzk.fitlog.domain.Muscle
import org.json.JSONArray
import org.json.JSONObject

class ExerciseCatalog(private val context: Context, private val database: FitLogDatabase) {
    suspend fun initialize() {
        val root = context.assets.open("exercises.json").bufferedReader().use { JSONObject(it.readText()) }
        val version = root.getInt("version")
        val entries = root.getJSONArray("exercises")
        require(version > 0 && entries.length() > 0)
        val exercises = mutableListOf<ExerciseEntity>()
        val muscles = mutableListOf<ExerciseMuscleEntity>()
        for (index in 0 until entries.length()) {
            val item = entries.getJSONObject(index)
            val id = item.getString("id")
            val name = item.getString("nameZh")
            require(id.isNotBlank() && name.isNotBlank())
            exercises += ExerciseEntity(id, name, item.getString("nameEn"),
                EquipmentType.valueOf(item.getString("equipmentType")).name,
                item.optString("instructions").ifBlank { null }, item.optInt("estimatedSecondsPerSet", 45), null, false, false, false)
            for (role in listOf("PRIMARY", "SECONDARY")) {
                val list = item.optJSONArray(if (role == "PRIMARY") "primaryMuscles" else "secondaryMuscles") ?: JSONArray()
                if (role == "PRIMARY") require(list.length() > 0)
                for (i in 0 until list.length()) {
                    muscles += ExerciseMuscleEntity(id, Muscle.valueOf(list.getString(i)).name, role)
                }
            }
        }
        require(exercises.distinctBy { it.id }.size == exercises.size)
        database.withTransaction {
            val dao = database.workoutDao()
            if ((dao.catalogVersion() ?: 0) >= version) return@withTransaction
            val training = database.trainingDao()
            val existing = training.allExercises().associateBy { it.id }
            dao.insertExercises(exercises)
            exercises.forEach { item ->
                val old = existing[item.id]
                if (old == null || !old.isCustom) {
                    if (old != null) training.updateExercise(item.copy(isFavorite = old.isFavorite, isArchived = old.isArchived))
                    training.deleteMuscles(item.id)
                    dao.insertMuscles(muscles.filter { it.exerciseId == item.id })
                }
            }
            dao.saveCatalogVersion(CatalogVersionEntity("builtin", version))
        }
    }
}
