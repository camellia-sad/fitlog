package dev.lzk.fitlog.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val FROM_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS template_drafts (
                id TEXT NOT NULL, templateId TEXT, content TEXT NOT NULL, PRIMARY KEY(id),
                FOREIGN KEY(templateId) REFERENCES workout_templates(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            db.execSQL("CREATE UNIQUE INDEX index_template_drafts_templateId ON template_drafts(templateId)")
        }
    }

    val FROM_2_TO_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS training_plans (id TEXT NOT NULL PRIMARY KEY, startDate TEXT NOT NULL, cycleDays INTEGER NOT NULL, repeats INTEGER NOT NULL)")
            db.execSQL("""CREATE TABLE IF NOT EXISTS plan_days (
                planId TEXT NOT NULL, dayIndex INTEGER NOT NULL, templateId TEXT,
                PRIMARY KEY(planId, dayIndex),
                FOREIGN KEY(planId) REFERENCES training_plans(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(templateId) REFERENCES workout_templates(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
            db.execSQL("CREATE INDEX index_plan_days_templateId ON plan_days(templateId)")
        }
    }

    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""CREATE TABLE IF NOT EXISTS workout_templates (
                id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, splitType TEXT NOT NULL,
                createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS template_exercises (
                id TEXT NOT NULL PRIMARY KEY, templateId TEXT NOT NULL, exerciseId TEXT NOT NULL, sortOrder INTEGER NOT NULL,
                targetSetCount INTEGER NOT NULL, targetRepMin INTEGER NOT NULL, targetRepMax INTEGER NOT NULL,
                defaultWeightGrams INTEGER NOT NULL, restSeconds INTEGER NOT NULL,
                FOREIGN KEY(templateId) REFERENCES workout_templates(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(exerciseId) REFERENCES exercises(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
            db.execSQL("CREATE UNIQUE INDEX index_template_exercises_templateId_sortOrder ON template_exercises(templateId, sortOrder)")
            db.execSQL("CREATE INDEX index_template_exercises_exerciseId ON template_exercises(exerciseId)")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN templateId TEXT REFERENCES workout_templates(id) ON UPDATE NO ACTION ON DELETE SET NULL")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN templateNameSnapshot TEXT")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN pausedAtUtc INTEGER")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN pausedDurationMillis INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE workout_sessions ADD COLUMN restEndsAtUtc INTEGER")
            db.execSQL("CREATE INDEX index_workout_sessions_templateId ON workout_sessions(templateId)")
            db.execSQL("ALTER TABLE session_exercises ADD COLUMN restSecondsSnapshot INTEGER")
            installGuards(db)
        }
    }

    fun installGuards(db: SupportSQLiteDatabase) {
        for (event in listOf("INSERT", "UPDATE")) {
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS set_guard_${event.lowercase()} BEFORE $event ON set_records
                WHEN NEW.weightGrams < 0 OR NEW.reps < 0 OR NEW.setIndex < 1
                OR NEW.status NOT IN ('PLANNED', 'COMPLETED', 'SKIPPED')
                OR (NEW.status = 'COMPLETED' AND (NEW.reps <= 0 OR NEW.completedAtUtc IS NULL OR NEW.completedAtUtc < 0))
                OR (NEW.status != 'COMPLETED' AND NEW.completedAtUtc IS NOT NULL)
                BEGIN SELECT RAISE(ABORT, 'invalid set record'); END
            """.trimIndent())
        }
    }
}
