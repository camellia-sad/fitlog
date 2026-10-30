package dev.lzk.fitlog.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "fitlog-migration-test.db"
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        FitLogDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())

    @Test fun versionThreePlanSurvivesAndSupportsTemplateDrafts() = runBlocking {
        val upgradedName = "fitlog-migration-v3-test.db"
        helper.createDatabase(upgradedName, 3).apply {
            execSQL("INSERT INTO workout_templates VALUES ('template','原有推日','FREE',1000,1000)")
            execSQL("INSERT INTO training_plans VALUES ('current','2026-10-26',7,1)")
            repeat(7) { index ->
                execSQL("INSERT INTO plan_days VALUES ('current', ?, ?)", arrayOf<Any?>(index, if (index == 0) "template" else null))
            }
            close()
        }
        helper.runMigrationsAndValidate(upgradedName, 4, true, DatabaseMigrations.FROM_3_TO_4).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = FitLogDatabase.create(context, upgradedName)
        try {
            val repo = RoomTrainingRepository(database)
            assertEquals("原有推日", repo.observeTemplates().first().single().name)
            assertEquals("template", repo.observePlan().first()!!.templateIds.first())
            repo.saveTemplateDraft(dev.lzk.fitlog.domain.TemplateDraft(id = "template", name = "未完成修改"))
            assertEquals("未完成修改", repo.observeTemplateDrafts().first().single().name)
        } finally { database.close(); context.deleteDatabase(upgradedName) }
    }

    @Test fun versionOneHistoryAndDraftSurviveVersionFour() = runBlocking {
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO exercises VALUES ('bench','卧推','Bench','BARBELL',NULL,NULL,NULL,0,0,0)")
            execSQL("INSERT INTO workout_sessions VALUES ('session',1000,'2026-10-28','ACTIVE',NULL,NULL,1)")
            execSQL("INSERT INTO session_exercises VALUES ('exercise','session','bench',0,'旧名称快照',2,8,12)")
            execSQL("INSERT INTO set_records VALUES ('done','exercise',1,42500,10,'COMPLETED',2000,'WORKING')")
            execSQL("INSERT INTO set_records VALUES ('draft','exercise',2,37500,8,'PLANNED',NULL,'WORKING')")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, DatabaseMigrations.FROM_1_TO_2, DatabaseMigrations.FROM_2_TO_3, DatabaseMigrations.FROM_3_TO_4).close()
        val database = FitLogDatabase.create(InstrumentationRegistry.getInstrumentation().targetContext, name)
        try {
            val workout = requireNotNull(RoomWorkoutRepository(database).observeActiveWorkout().first())
            assertEquals("旧名称快照", workout.exercises.single().exerciseNameSnapshot)
            assertEquals(42_500L, workout.exercises.single().sets.first().weightGrams)
            assertEquals(37_500L, workout.exercises.single().sets.last().weightGrams)
            assertEquals(1, workout.completedSetCount)
            assertEquals(0L, workout.session.pausedDurationMillis)
            assertNull(workout.session.templateId)
        } finally { database.close() }
    }
}
