package dev.lzk.fitlog.domain

import java.util.UUID

/** A template as copied to the clipboard. Custom exercises carry their definition so another device can recreate them. */
data class SharedTemplate(
    val name: String, val shortName: String?, val splitType: SplitType, val exercises: List<SharedTemplateExercise>,
)
data class SharedTemplateExercise(
    val exerciseId: String, val name: String, val nameEn: String = "", val recordMetric: RecordMetric = RecordMetric.REPETITIONS,
    val sets: Int, val repMin: Int, val repMax: Int, val restSeconds: Int,
    val useExerciseWeight: Boolean = true, val weightGrams: Long = 0,
    /** Present only for custom exercises. */
    val custom: SharedExerciseDefinition? = null,
)
data class SharedExerciseDefinition(val equipment: EquipmentType, val primaryMuscles: Set<Muscle>, val secondaryMuscles: Set<Muscle> = emptySet())

/**
 * The editor contents an import produces. Exercises this device lacks but whose definition came along use a
 * [TemplateTransfer.PENDING] id; they are created in the library only when the template (or its draft) is kept.
 */
data class TemplateImport(val draft: TemplateDraft, val pending: Map<String, CustomExerciseDraft>, val skipped: List<String>)

object TemplateTransfer {
    const val FORMAT = "fitlog.template"
    const val VERSION = 1
    /** Clipboard label: lets the app recognise its own copies without reading other clipboard contents. */
    const val CLIP_LABEL = "FitLog 模板"
    const val PENDING = "pending:"

    fun isPending(exerciseId: String) = exerciseId.startsWith(PENDING)

    fun share(template: WorkoutTemplate, catalog: List<Exercise>): SharedTemplate =
        SharedTemplate(template.name, template.shortName, template.splitType, template.exercises.sortedBy { it.sortOrder }.mapNotNull { item ->
            val exercise = catalog.find { it.id == item.exerciseId } ?: return@mapNotNull null
            SharedTemplateExercise(exercise.id, exercise.nameZh, exercise.nameEn, exercise.recordMetric, item.targetSetCount, item.targetRepMin,
                item.targetRepMax, item.restSeconds, item.useExerciseWeight, item.defaultWeightGrams,
                if (exercise.isCustom) SharedExerciseDefinition(exercise.equipmentType, exercise.primaryMuscles, exercise.secondaryMuscles) else null)
        })

    /** The same limits as the editor; each message names the exercise it is about. */
    fun validate(template: SharedTemplate) {
        WorkoutRules.input(template.name.trim().length in 1..80, "模板名称须为 1～80 字")
        WorkoutRules.input(template.exercises.size in 1..30, "模板须包含 1～30 个动作")
        template.exercises.forEachIndexed { index, item ->
            try {
                WorkoutRules.input(item.name.trim().length in 1..80, "动作名称须为 1～80 字")
                WorkoutRules.setCount(item.sets)
                WorkoutRules.repRange(item.repMin, item.repMax)
                WorkoutRules.rest(item.restSeconds)
                WorkoutRules.draft(item.weightGrams, 0)
                item.custom?.let { CustomExerciseDraft(item.name, item.nameEn, it.equipment, it.primaryMuscles, "", it.secondaryMuscles, item.recordMetric).validate() }
            } catch (e: WorkoutException) { throw WorkoutException(WorkoutError.INVALID_INPUT, "第 ${index + 1} 个动作：${e.message}") }
        }
    }

    /**
     * Matches exercises by id, then by Chinese name among active ones; a custom exercise found neither way becomes a
     * pending one, anything else is skipped. A name already used gets “（2）”; the short name is kept when free.
     */
    fun toDraft(template: SharedTemplate, catalog: List<Exercise>, existing: List<WorkoutTemplate>, newId: () -> String = { UUID.randomUUID().toString() }): TemplateImport {
        validate(template)
        val active = catalog.filter { !it.isArchived }
        val pending = LinkedHashMap<String, CustomExerciseDraft>()
        val pendingByName = HashMap<String, String>()
        val skipped = mutableListOf<String>()
        val items = template.exercises.mapNotNull { item ->
            val key = item.name.trim()
            val exerciseId = active.find { it.id == item.exerciseId }?.id
                ?: active.find { it.nameZh.trim() == key }?.id
                ?: item.custom?.let { definition ->
                    pendingByName.getOrPut(key) {
                        (PENDING + newId()).also { id ->
                            pending[id] = CustomExerciseDraft(key, item.nameEn.trim(), definition.equipment, definition.primaryMuscles, "",
                                definition.secondaryMuscles, item.recordMetric)
                        }
                    }
                }
            if (exerciseId == null) { skipped += key; return@mapNotNull null }
            exerciseId to item
        }
        WorkoutRules.input(items.isNotEmpty(), "模板中的动作本机都没有")
        val names = existing.map { it.name.trim() }.toSet()
        var name = template.name.trim()
        if (name in names) { var n = 2; while ("${template.name.trim()}（$n）" in names) n++; name = "${template.name.trim()}（$n）" }
        val labels = existing.map { TemplateLabel(it.id, it.name, it.shortName) }
        val used = existing.mapNotNull { it.shortName }.toSet()
        // Keep the shared short name when free; after a rename prefer its first character plus a number (推 → 推2),
        // since deriving from “推日（2）” would pick up the bracket.
        val shared = template.shortName?.trim()?.takeIf { TemplateShortNames.isValid(it) }
        val first = shared?.let { String(Character.toChars(it.codePointAt(0))) }
        val short = shared?.takeIf { it !in used }
            ?: first?.let { f -> (2..9).map { "$f$it" }.firstOrNull { it !in used } }
            ?: TemplateShortNames.suggest(template.name.trim(), labels, used)
        val exercises = items.mapIndexed { index, (exerciseId, item) ->
            TemplateExercise(newId(), exerciseId, index, item.sets, item.repMin, item.repMax,
                if (item.useExerciseWeight) 0 else item.weightGrams, item.restSeconds, item.useExerciseWeight)
        }
        return TemplateImport(TemplateDraft(null, name, template.splitType, exercises, short.ifEmpty { null }),
            pending.filterKeys { id -> exercises.any { it.exerciseId == id } }, skipped)
    }
}
