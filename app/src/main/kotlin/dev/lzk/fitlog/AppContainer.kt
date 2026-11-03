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
    val networkDiagnostics = NetworkDiagnostics(context.applicationContext, databaseName)
    init { scope.launch { runCatching { networkDiagnostics.prune() } } }
    val backups = JsonBackupRepository(database, settingsStore, settingsStore)
    private val socialDelegate = lazy {
        SupabaseSocialRepository(context.applicationContext, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY, databaseName)
    }
    val social get() = socialDelegate.value
    val friends: FriendsRepository get() = social
    val portableBackups by lazy {PortableBackupRepository(backups,social)}
    val updates by lazy {dev.lzk.fitlog.updates.AppUpdates(context,BuildConfig.UPDATE_MANIFEST_URL,databaseName)}
    val sharing by lazy { dev.lzk.fitlog.features.TrainingSharing(scope, workouts, training, social) }
    val ready = scope.async { ExerciseCatalog(context.applicationContext, database).initialize() }
    fun initialize() { ready.start() }
    override fun close() { scope.cancel(); if (socialDelegate.isInitialized()) social.close(); database.close() }
}
