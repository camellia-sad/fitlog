package dev.lzk.fitlog.domain

import java.math.BigDecimal

enum class WorkoutError { NOT_FOUND, INVALID_INPUT, SESSION_NOT_ACTIVE, SESSION_ENDED, SET_NOT_EDITABLE }

class WorkoutException(val error: WorkoutError, message: String) : IllegalStateException(message)

object WorkoutRules {
    const val DEFAULT_WEIGHT_STEP_GRAMS = 2_500L
    const val DEFAULT_SET_COUNT = 3
    const val DEFAULT_REP_MIN = 8
    const val DEFAULT_REP_MAX = 12
    const val MAX_SET_COUNT = 100

    fun input(condition: Boolean, message: String) {
        if (!condition) throw WorkoutException(WorkoutError.INVALID_INPUT, message)
    }

    fun draft(weightGrams: Long, reps: Int) {
        input(weightGrams >= 0, "重量不能为负数")
        input(reps >= 0, "次数不能为负数")
    }

    fun completion(weightGrams: Long, reps: Int) {
        draft(weightGrams, reps)
        input(reps > 0, "完成组的次数必须大于零")
    }

    fun rest(seconds: Int) = input(seconds in 0..3600, "休息时长须在 0～3600 秒之间")

    fun setCount(count: Int) = input(count in 1..MAX_SET_COUNT, "组数须在 1 到 $MAX_SET_COUNT 之间")
    fun repRange(min: Int, max: Int) = input(min > 0 && max >= min, "目标次数范围无效")

    fun active(status: SessionStatus) {
        if (status != SessionStatus.ACTIVE) throw WorkoutException(WorkoutError.SESSION_NOT_ACTIVE, "会话不处于训练中")
    }
}

object WeightInput {
    fun kilogramsToGrams(text: String): Long = try {
        val value = text.trim()
        WorkoutRules.input(value.matches(Regex("[0-9]+(?:\\.[0-9]+)?")), "请输入非负重量")
        BigDecimal(value).movePointRight(3).longValueExact().also { WorkoutRules.draft(it, 0) }
    } catch (e: ArithmeticException) {
        throw WorkoutException(WorkoutError.INVALID_INPUT, "重量超出范围或不足一克")
    } catch (e: NumberFormatException) {
        throw WorkoutException(WorkoutError.INVALID_INPUT, "重量格式无效")
    }

    fun gramsToKilograms(grams: Long): String {
        WorkoutRules.draft(grams, 0)
        return BigDecimal.valueOf(grams, 3).stripTrailingZeros().toPlainString()
    }
}
