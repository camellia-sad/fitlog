package dev.lzk.fitlog.domain

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.Flow

enum class WeightUnit(val label: String) { KG("kg"), LB("lb") }
enum class SplitType { FULL_BODY, UPPER_LOWER, PUSH_PULL_LEGS, FREE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class UserPreferences(
    val equipment: Set<EquipmentType> = emptySet(),
    val muscles: Set<Muscle> = emptySet(),
    val split: SplitType = SplitType.FREE,
    val workoutMinutes: Int = 45,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val weightStepGrams: Long = WorkoutRules.DEFAULT_WEIGHT_STEP_GRAMS,
    val restEnabled: Boolean = true,
    val restSeconds: Int = 90,
    val onboardingCompleted: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val hapticsEnabled: Boolean = true,
) {
    fun validate() {
        WorkoutRules.input(workoutMinutes in 5..360, "训练时长须在 5～360 分钟之间")
        WorkoutRules.input(weightStepGrams > 0, "重量步长须大于零")
        WorkoutRules.rest(restSeconds)
    }
}

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun save(preferences: UserPreferences)
}

object WeightUnits {
    private val gramsPerPound = BigDecimal("453.59237")

    fun parse(text: String, unit: WeightUnit): Long {
        if (unit == WeightUnit.KG) return WeightInput.kilogramsToGrams(text)
        WorkoutRules.input(text.trim().matches(Regex("[0-9]+(?:\\.[0-9]+)?")), "请输入非负重量")
        return try {
            BigDecimal(text.trim()).multiply(gramsPerPound).setScale(0, RoundingMode.HALF_UP).longValueExact()
                .also { WorkoutRules.draft(it, 0) }
        } catch (error: ArithmeticException) {
            throw WorkoutException(WorkoutError.INVALID_INPUT, "重量超出范围")
        }
    }

    fun format(grams: Long, unit: WeightUnit, editing: Boolean = false): String {
        WorkoutRules.draft(grams, 0)
        return if (unit == WeightUnit.KG) WeightInput.gramsToKilograms(grams)
        else BigDecimal.valueOf(grams).divide(gramsPerPound, if (editing) 6 else 2, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }
}
