package dev.lzk.fitlog.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TemplateDraftRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "template-drafts-test.db"
    private lateinit var db: FitLogDatabase
    private lateinit var repo: RoomTrainingRepository
    @Before fun setup() = runBlocking {
        context.deleteDatabase(name)
        db = FitLogDatabase.create(context, name)
        ExerciseCatalog(context, db).initialize()
        repo = RoomTrainingRepository(db)
    }
    @After fun teardown() { db.close(); context.deleteDatabase(name) }

    private fun draft(name: String = "自己的推日", id: String? = null) = TemplateDraft(id, name, SplitType.PUSH_PULL_LEGS,
        listOf(TemplateExercise("draft-set", "barbell-bench-press", 0, 2, 6, 10, 42_500, 120)))

    @Test fun incompleteDraftSurvivesDatabaseReopenAndFailedFinalSave() = runBlocking {
        val value = draft("")
        repo.saveTemplateDraft(value)
        assertTrue(repo.observeTemplates().first().isEmpty())
        db.close()
        db = FitLogDatabase.create(context, name)
        repo = RoomTrainingRepository(db)
        assertEquals(value, repo.observeTemplateDrafts().first().single())
        try { repo.saveTemplate(value); fail("Incomplete draft cannot become a template") }
        catch (_: WorkoutException) { }
        assertEquals(value, repo.observeTemplateDrafts().first().single())
        assertTrue(repo.observeTemplates().first().isEmpty())
    }

    @Test fun savingTemplateAtomicallyRemovesOnlyItsOwnDraft() = runBlocking {
        val id = repo.saveTemplate(draft())
        val edited = draft("调整后的推日", id)
        val new = draft("尚未完成的新模板")
        repo.saveTemplateDraft(edited)
        repo.saveTemplateDraft(new)
        assertEquals("自己的推日", repo.observeTemplates().first().single().name)
        repo.saveTemplate(edited)
        assertEquals(listOf(new), repo.observeTemplateDrafts().first())
        assertEquals("调整后的推日", repo.observeTemplates().first().single().name)
        repo.saveTemplate(new)
        assertTrue(repo.observeTemplateDrafts().first().isEmpty())
        assertEquals(2, repo.observeTemplates().first().size)
        assertTrue(repo.observeTemplates().first().all { it.exercises.single().defaultWeightGrams == 42_500L })
    }

    @Test fun discardingOrDeletingExistingTemplateKeepsOtherDrafts() = runBlocking {
        val id = repo.saveTemplate(draft())
        val edited = draft("未保存的修改", id)
        val new = TemplateDraft(name = "只写了名称")
        repo.saveTemplateDraft(edited)
        repo.saveTemplateDraft(new)
        repo.deleteTemplateDraft(null)
        assertEquals(listOf(edited), repo.observeTemplateDrafts().first())
        assertEquals("自己的推日", repo.observeTemplates().first().single().name)
        repo.saveTemplateDraft(new)
        repo.deleteTemplate(id)
        assertEquals(listOf(new), repo.observeTemplateDrafts().first())
    }
}
