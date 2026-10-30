package dev.lzk.fitlog

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.features.SettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupFileFlowTest {
    @Test fun exportImportUsesRealFilesAndInvalidFileLeavesDataIntact() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val names = listOf("backup-source-ui-test.db", "backup-target-ui-test.db")
        names.forEach { context.deleteDatabase(it) }
        val source = AppContainer(context, names[0])
        val target = AppContainer(context, names[1])
        val store = ViewModelStore()
        val file = java.io.File(context.cacheDir, "fitlog-file-flow-test.json")
        try {
            source.ready.await(); target.ready.await()
            val workout = source.workouts.startWorkout()
            val exercise = source.workouts.addExercise(workout.session.id, "barbell-bench-press", 1, 8, 12)
            val set = source.workouts.observeActiveWorkout().first()!!.exercises.single().sets.single()
            source.workouts.completeSet(exercise, set.id, 42_500, 10)
            source.workouts.finishWorkout(workout.session.id, "文件备份")
            val exporter = SettingsViewModel(source)
            val importer = SettingsViewModel(target)
            store.put("export", exporter); store.put("import", importer)
            exporter.export(context, Uri.fromFile(file)).join()
            assertEquals("训练与模板备份已导出", exporter.message.value)
            assertTrue(file.length() > 0)
            importer.import(context, Uri.fromFile(file)).join()
            assertTrue(importer.message.value.orEmpty().startsWith("导入完成"))
            val restored = target.workouts.observeWorkout(workout.session.id).first()!!
            assertEquals(42_500L, restored.exercises.single().sets.single().weightGrams)
            assertEquals("文件备份", restored.session.feedback)
            val bad = JSONObject(file.readText())
            bad.getJSONObject("data").getJSONArray("set_records").getJSONObject(0).put("weightGrams", 1.5)
            file.writeText(bad.toString())
            importer.import(context, Uri.fromFile(file)).join()
            assertTrue(importer.message.value.orEmpty().contains("备份整数格式错误"))
            assertEquals(restored, target.workouts.observeWorkout(workout.session.id).first())
        } finally {
            store.clear()
            source.close(); target.close()
            names.forEach { context.deleteDatabase(it) }
            file.delete()
        }
    }
}
