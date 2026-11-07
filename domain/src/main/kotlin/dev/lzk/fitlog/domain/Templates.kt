package dev.lzk.fitlog.domain

import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class TemplateExercise(
    val id: String,
    val exerciseId: String,
    val sortOrder: Int,
    val targetSetCount: Int = 3,
    val targetRepMin: Int = 8,
    val targetRepMax: Int = 12,
    val defaultWeightGrams: Long = 0,
    val restSeconds: Int = 90,
    val useExerciseWeight: Boolean = defaultWeightGrams == 0L,
)

data class WorkoutTemplate(
    val id: String, val name: String, val splitType: SplitType,
    val createdAt: Instant, val updatedAt: Instant, val exercises: List<TemplateExercise>,
    /** Label for the plan timetable; null on templates saved before short names existed (one is derived for display). */
    val shortName: String? = null,
)

data class TemplateDraft(
    val id: String? = null, val name: String = "", val splitType: SplitType = SplitType.FREE,
    val exercises: List<TemplateExercise> = emptyList(), val shortName: String? = null,
) {
    fun validate() {
        WorkoutRules.input(name.trim().length in 1..80, "模板名称须为 1～80 字")
        shortName?.let { WorkoutRules.input(TemplateShortNames.isValid(it), "简称须为 1～${TemplateShortNames.MAX_LENGTH} 个字") }
        WorkoutRules.input(exercises.size in 1..30, "模板须包含 1～30 个动作")
        WorkoutRules.input(exercises.map { it.id }.distinct().size == exercises.size, "模板动作 ID 重复")
        exercises.forEach {
            WorkoutRules.setCount(it.targetSetCount)
            WorkoutRules.repRange(it.targetRepMin, it.targetRepMax)
            WorkoutRules.draft(it.defaultWeightGrams, 0)
            WorkoutRules.rest(it.restSeconds)
        }
    }
}

/** A template as far as short names are concerned. */
data class TemplateLabel(val id: String, val name: String, val shortName: String?)

/**
 * Short names shown in the plan timetable (cells fit two characters). The default is the name's first character;
 * when another template starts with the same character, the character right after the longest shared prefix is
 * added (胸肩日 / 胸背日 → 胸肩 / 胸背). A name that is entirely a prefix of another keeps its first two characters,
 * and a clash with a short name already in use falls back to the first character plus a number.
 */
object TemplateShortNames {
    const val MAX_LENGTH = 2

    fun isValid(value: String): Boolean = value.isNotBlank() && value == value.trim() && length(value) in 1..MAX_LENGTH

    fun suggest(name: String, others: List<TemplateLabel>, used: Set<String> = others.mapNotNull { it.shortName }.toSet()): String {
        val chars = name.trim().codePoints().toArray()
        if (chars.isEmpty()) return ""
        val first = chars[0]
        val peers = others.map { it.name.trim().codePoints().toArray() }.filter { it.isNotEmpty() && it[0] == first }
        var value = text(first)
        if (peers.isNotEmpty()) {
            val shared = peers.maxOf { peer -> var k = 0; while (k < peer.size && k < chars.size && peer[k] == chars[k]) k++; k }
            value = when {
                shared < chars.size -> text(first) + text(chars[shared])
                chars.size > 1 -> text(first) + text(chars[1])
                else -> value
            }
        }
        var number = 2
        while (value in used) value = text(first) + number++
        return value
    }

    /**
     * Short name per template id. Stored names win (the first holder keeps a duplicated one); templates without a
     * stored name get the default against everyone else, in creation order, never reusing a name already given out.
     */
    fun resolve(labels: List<TemplateLabel>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val used = HashSet<String>()
        labels.forEach { label -> label.shortName?.takeIf { isValid(it) && used.add(it) }?.let { result[label.id] = it } }
        labels.filter { it.id !in result }.forEach { label ->
            val value = suggest(label.name, labels.filter { it.id != label.id }, used).ifEmpty { "?" }
            used += value; result[label.id] = value
        }
        return labels.associate { it.id to result.getValue(it.id) }
    }

    private fun length(value: String) = value.codePointCount(0, value.length)
    private fun text(codePoint: Int) = String(Character.toChars(codePoint))
}

fun WorkoutTemplate.label() = TemplateLabel(id, name, shortName)
fun List<WorkoutTemplate>.shortNames(): Map<String, String> =
    TemplateShortNames.resolve(sortedWith(compareBy<WorkoutTemplate> { it.createdAt }.thenBy { it.id }).map { it.label() })

interface TemplateDraftRepository {
    fun observeTemplateDrafts(): Flow<List<TemplateDraft>>
    suspend fun saveTemplateDraft(draft: TemplateDraft)
    suspend fun deleteTemplateDraft(templateId: String?)
}

interface TemplateRepository {
    fun observeTemplates(): Flow<List<WorkoutTemplate>>
    suspend fun saveTemplate(draft: TemplateDraft): String
    suspend fun deleteTemplate(id: String)
}

class SaveTemplateUseCase(private val repository: TemplateRepository) {
    suspend operator fun invoke(draft: TemplateDraft): String { draft.validate(); return repository.saveTemplate(draft) }
}
