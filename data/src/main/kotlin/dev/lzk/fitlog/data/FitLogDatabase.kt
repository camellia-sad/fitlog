package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ExerciseEntity::class, ExerciseMuscleEntity::class, CatalogVersionEntity::class,
        SessionEntity::class, SessionExerciseEntity::class, SetEntity::class,
        TemplateEntity::class, TemplateExerciseEntity::class, TrainingPlanEntity::class, PlanDayEntity::class, TemplateDraftEntity::class],
    version = 10, exportSchema = true,
)
abstract class FitLogDatabase : RoomDatabase() {
    abstract fun workoutDao(): WorkoutDao
    abstract fun trainingDao(): TrainingDao

    companion object {
        fun create(context: Context, name: String = "fitlog.db"): FitLogDatabase =
            configure(Room.databaseBuilder(context.applicationContext, FitLogDatabase::class.java, name)).build()

        fun configure(builder: Builder<FitLogDatabase>): Builder<FitLogDatabase> =
            builder.addMigrations(DatabaseMigrations.FROM_1_TO_2, DatabaseMigrations.FROM_2_TO_3, DatabaseMigrations.FROM_3_TO_4, DatabaseMigrations.FROM_4_TO_5, DatabaseMigrations.FROM_5_TO_6, DatabaseMigrations.FROM_6_TO_7, DatabaseMigrations.FROM_7_TO_8, DatabaseMigrations.FROM_8_TO_9, DatabaseMigrations.FROM_9_TO_10).addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = DatabaseMigrations.installGuards(db)
            })
    }
}
