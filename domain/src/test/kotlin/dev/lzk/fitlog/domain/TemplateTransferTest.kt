package dev.lzk.fitlog.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class TemplateTransferTest {
    private val bench = Exercise("bench_press", "杠铃卧推", "Bench Press", EquipmentType.BARBELL, setOf(Muscle.CHEST))
    private val myRow = Exercise("local-uuid", "弹力带划船", "", EquipmentType.CABLE, setOf(Muscle.BACK), isCustom = true)
    private var counter = 0
    private val ids = { "id-${++counter}" }
    private fun item(id: String, name: String, custom: SharedExerciseDefinition? = null, sets: Int = 4) =
        SharedTemplateExercise(id, name, sets = sets, repMin = 6, repMax = 10, restSeconds = 120, useExerciseWeight = false, weightGrams = 60_000, custom = custom)

    @Test fun shareCarriesDefinitionsOnlyForCustomExercises() {
        val now = Instant.now()
        val template = WorkoutTemplate("t", "推日", SplitType.PUSH_PULL_LEGS, now, now, listOf(
            TemplateExercise("a", "local-uuid", 1, 3, 10, 12, 0, 60), TemplateExercise("b", "bench_press", 0, 5, 5, 5, 80_000, 180, false)), "推")
        val shared = TemplateTransfer.share(template, listOf(bench, myRow))
        assertEquals(listOf("bench_press", "local-uuid"), shared.exercises.map { it.exerciseId })
        assertNull(shared.exercises[0].custom)
        assertEquals(EquipmentType.CABLE, shared.exercises[1].custom?.equipment)
        assertEquals(80_000L, shared.exercises[0].weightGrams)
    }

    @Test fun importMatchesByIdThenNameAndQueuesUnknownCustomExercises() {
        val elsewhere = Exercise("other-uuid", "弹力带划船", "", EquipmentType.CABLE, setOf(Muscle.BACK), isCustom = true)
        val shared = SharedTemplate("推日", "推", SplitType.PUSH_PULL_LEGS, listOf(
            item("bench_press", "杠铃卧推"),
            item("local-uuid", "弹力带划船", SharedExerciseDefinition(EquipmentType.CABLE, setOf(Muscle.BACK))),
            item("x-uuid", "壶铃摆荡", SharedExerciseDefinition(EquipmentType.DUMBBELL, setOf(Muscle.GLUTES)), sets = 2),
            item("x-uuid-2", "壶铃摆荡", SharedExerciseDefinition(EquipmentType.DUMBBELL, setOf(Muscle.GLUTES))),
            item("future_builtin", "新内置动作"),
        ))
        val result = TemplateTransfer.toDraft(shared, listOf(bench, elsewhere), emptyList(), ids)
        val exerciseIds = result.draft.exercises.map { it.exerciseId }
        assertEquals("bench_press", exerciseIds[0])
        assertEquals("other-uuid", exerciseIds[1])
        assertTrue(TemplateTransfer.isPending(exerciseIds[2]))
        assertEquals(exerciseIds[2], exerciseIds[3])
        assertEquals(1, result.pending.size)
        assertEquals("壶铃摆荡", result.pending.values.single().name)
        assertEquals(listOf("新内置动作"), result.skipped)
        assertEquals(listOf(0, 1, 2, 3), result.draft.exercises.map { it.sortOrder })
        assertNull(result.draft.id)
        assertEquals("推", result.draft.shortName)
        assertEquals(60_000L, result.draft.exercises[0].defaultWeightGrams)
        result.draft.validate()
    }

    @Test fun clashingNamesAndShortNamesAreRenamed() {
        val now = Instant.now()
        val existing = listOf(WorkoutTemplate("a", "推日", SplitType.FREE, now, now, emptyList(), "推"),
            WorkoutTemplate("b", "推日（2）", SplitType.FREE, now, now, emptyList(), "推2"))
        val result = TemplateTransfer.toDraft(SharedTemplate("推日", "推", SplitType.FREE, listOf(item("bench_press", "杠铃卧推"))),
            listOf(bench), existing, ids)
        assertEquals("推日（3）", result.draft.name)
        assertEquals("推3", result.draft.shortName)
    }

    @Test fun validationNamesTheFaultyExerciseAndNothingKnownFails() {
        val bad = SharedTemplate("推日", null, SplitType.FREE, listOf(item("bench_press", "杠铃卧推"), item("bench_press", "杠铃卧推", sets = 0)))
        val error = assertThrows(WorkoutException::class.java) { TemplateTransfer.validate(bad) }
        assertTrue(error.message!!.startsWith("第 2 个动作"))
        val unknown = SharedTemplate("推日", null, SplitType.FREE, listOf(item("nope", "不存在")))
        assertThrows(WorkoutException::class.java) { TemplateTransfer.toDraft(unknown, listOf(bench), emptyList(), ids) }
    }
}
