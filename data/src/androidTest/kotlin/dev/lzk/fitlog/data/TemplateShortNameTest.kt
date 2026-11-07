package dev.lzk.fitlog.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TemplateShortNameTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        FitLogDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    private fun item(id: String = UUID.randomUUID().toString()) = TemplateExercise(id, "barbell-bench-press", 0)

    @Test fun versionSevenTemplatesMigrateWithoutShortNameAndGetDerivedOnes() = runBlocking {
        val name = "short-migration-" + UUID.randomUUID()
        helper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO workout_templates(id,name,splitType,createdAt,updatedAt) VALUES('a','胸肩日','FREE',1,1),('b','胸背日','FREE',2,2)")
            close()
        }
        helper.runMigrationsAndValidate(name, 8, true, DatabaseMigrations.FROM_7_TO_8).close()
        val db = FitLogDatabase.create(context, name)
        try {
            assertNull(db.trainingDao().allTemplates().first { it.id == "a" }.shortName)
            val labels = db.trainingDao().allTemplates().sortedBy { it.createdAt }.map { TemplateLabel(it.id, it.name, it.shortName) }
            assertEquals(mapOf("a" to "胸肩", "b" to "胸背"), TemplateShortNames.resolve(labels))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun savingRejectsDuplicateNamesAndShortNamesAndBackupKeepsThem() = runBlocking {
        val aName = "short-" + UUID.randomUUID(); val bName = "short-" + UUID.randomUUID()
        val a = FitLogDatabase.create(context, aName); val b = FitLogDatabase.create(context, bName)
        try {
            ExerciseCatalog(context, a).initialize(); ExerciseCatalog(context, b).initialize()
            val repo = RoomTrainingRepository(a)
            val first = repo.saveTemplate(TemplateDraft(name = "胸肩日", exercises = listOf(item()), shortName = "胸肩"))
            try { repo.saveTemplate(TemplateDraft(name = " 胸肩日 ", exercises = listOf(item()))); fail("duplicate name accepted") }
            catch (e: WorkoutException) { assertTrue(e.message!!.contains("同名")) }
            try { repo.saveTemplate(TemplateDraft(name = "胸背日", exercises = listOf(item()), shortName = "胸肩")); fail("duplicate short name accepted") }
            catch (e: WorkoutException) { assertTrue(e.message!!.contains("胸肩日")) }
            try { repo.saveTemplate(TemplateDraft(name = "胸背日", exercises = listOf(item()), shortName = "胸背日")); fail("long short name accepted") }
            catch (_: WorkoutException) { }
            repo.saveTemplate(TemplateDraft(name = "胸背日", exercises = listOf(item()), shortName = "胸背"))
            // Renaming the template itself keeps its own short name available.
            val kept = repo.observeTemplates().first().first { it.id == first }
            repo.saveTemplate(TemplateDraft(first, "胸肩日改", kept.splitType, kept.exercises, "胸肩"))
            repo.saveTemplateDraft(TemplateDraft(name = "草稿", shortName = "草"))
            assertEquals("草", a.trainingDao().allDrafts().single().toDraft().shortName)

            val json = JsonBackupRepository(a).exportJson()
            assertEquals(7, JSONObject(json).getInt("schemaVersion"))
            JsonBackupRepository(b).importJson(json)
            assertEquals(setOf("胸肩", "胸背"), b.trainingDao().allTemplates().map { it.shortName }.toSet())
            // An older backup without short names still re-imports over the same templates.
            val legacy = JSONObject(json).put("schemaVersion", 6)
            legacy.getJSONObject("data").getJSONArray("workout_templates").let { rows -> (0 until rows.length()).forEach { rows.getJSONObject(it).remove("shortName") } }
            JsonBackupRepository(b).importJson(legacy.toString())
            assertEquals(2, b.trainingDao().allTemplates().size)
        } finally { a.close(); b.close(); context.deleteDatabase(aName); context.deleteDatabase(bName) }
    }
}
