package dev.lzk.fitlog.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateShortNamesTest {
    private fun label(id: String, name: String, short: String? = null) = TemplateLabel(id, name, short)

    @Test fun uniqueFirstCharacterIsEnough() {
        assertEquals("腿", TemplateShortNames.suggest("腿日", listOf(label("a", "胸肩日"))))
    }

    @Test fun sharedPrefixAddsTheFirstDifferingCharacter() {
        assertEquals("ac", TemplateShortNames.suggest("abcd", listOf(label("b", "abdddddd"))))
        assertEquals("ad", TemplateShortNames.suggest("abdddddd", listOf(label("a", "abcd"))))
        assertEquals("胸背", TemplateShortNames.suggest("胸背日", listOf(label("a", "胸肩日"), label("c", "腿日"))))
    }

    @Test fun prefixNamesAndClashesStayDistinct() {
        assertEquals("推2", TemplateShortNames.suggest("推日2", listOf(label("a", "推日"))))
        assertEquals("推日", TemplateShortNames.suggest("推日", listOf(label("b", "推日2"))))
        assertEquals("胸2", TemplateShortNames.suggest("胸", listOf(label("a", "胸背日", "胸"))))
        assertEquals("", TemplateShortNames.suggest("  ", emptyList()))
    }

    @Test fun resolveKeepsStoredNamesAndDerivesTheRest() {
        val names = TemplateShortNames.resolve(listOf(label("a", "胸肩日", "肩"), label("b", "胸背日"), label("c", "腿日", "肩")))
        assertEquals("肩", names["a"])
        assertEquals("胸背", names["b"])
        assertEquals("腿", names["c"])  // duplicated stored name falls back to the default
        assertEquals(3, names.values.distinct().size)
    }

    @Test fun validation() {
        assertTrue(TemplateShortNames.isValid("胸背"))
        assertTrue(TemplateShortNames.isValid("😀"))
        assertFalse(TemplateShortNames.isValid("胸背日"))
        assertFalse(TemplateShortNames.isValid(" 胸"))
        assertFalse(TemplateShortNames.isValid(""))
    }
}
