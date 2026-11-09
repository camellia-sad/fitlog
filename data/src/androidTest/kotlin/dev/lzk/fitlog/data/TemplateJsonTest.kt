package dev.lzk.fitlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.lzk.fitlog.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TemplateJsonTest {
    private val template = SharedTemplate("推日", "推", SplitType.PUSH_PULL_LEGS, listOf(
        SharedTemplateExercise("bench_press", "杠铃卧推", "Bench Press", RecordMetric.REPETITIONS, 4, 6, 10, 120, false, 62_500),
        SharedTemplateExercise("u-1", "弹力带划船", "", RecordMetric.SECONDS, 3, 30, 45, 60, true, 0,
            SharedExerciseDefinition(EquipmentType.CABLE, setOf(Muscle.BACK), setOf(Muscle.BICEPS))),
    ))

    @Test fun roundTripsAndToleratesSurroundingText() {
        val text = TemplateJson.encode(template)
        assertEquals(template, TemplateJson.decode(text))
        assertEquals(template, TemplateJson.decode("看看这个模板：\n$text\n"))
        assertTrue(TemplateJson.looksLikeTemplate(text))
    }

    @Test fun rejectsOtherTextNewerVersionsAndBadValuesWithAShortReason() {
        fun reason(text: String) = assertThrows(WorkoutException::class.java) { TemplateJson.decode(text) }.message
        assertEquals("剪贴板里不是 FitLog 模板", reason("今天练腿"))
        val text = TemplateJson.encode(template)
        assertEquals("模板来自更新版本的 FitLog，请先升级", reason(text.replace("\"version\":1", "\"version\":9")))
        assertEquals("模板内容不完整，请重新复制", reason(text.dropLast(30) + "}"))
        assertTrue(reason(text.replace("\"sets\":4", "\"sets\":0"))!!.startsWith("第 1 个动作"))
        assertTrue(reason(text.replace("\"sets\":4", "\"sets\":\"4\""))!!.startsWith("第 1 个动作"))
        assertTrue(reason(text.replace("\"CABLE\"", "\"ROPE\""))!!.startsWith("第 2 个动作"))
    }
}
