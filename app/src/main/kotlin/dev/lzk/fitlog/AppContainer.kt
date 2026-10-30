package dev.lzk.fitlog

import android.content.Context
import dev.lzk.fitlog.data.*
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.*

class AppContainer(context: Context, databaseName: String = "fitlog.db") : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database = FitLogDatabase.create(context, databaseName)
    val workouts: WorkoutRepository = RoomWorkoutRepository(database)
    val training = RoomTrainingRepository(database)
    private val settingsStore = UserPreferencesStore(context.applicationContext, scope, "$databaseName.preferences_pb")
    val preferences: PreferencesRepository = settingsStore
    val preparation: PreparationRepository = settingsStore
    val backups: BackupRepository = JsonBackupRepository(database)
    val ready = scope.async { ExerciseCatalog(context.applicationContext, database).initialize() }
    fun initialize() { ready.start() }
    override fun close() { scope.cancel(); database.close() }
}
