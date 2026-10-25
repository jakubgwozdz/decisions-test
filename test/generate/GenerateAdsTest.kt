package generate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import prepare.UnitGroup

class GenerateAdsTest {

    @Test
    fun slugifiesLabels() {
        assertEquals(
            "senior-officials-of-special-interest-organizations",
            slugify("Senior Officials of Special-interest Organizations"),
        )
        assertEquals(
            "technical-and-medical-sales-professionals-excluding-ict",
            slugify("Technical and Medical Sales Professionals (excluding ICT)"),
        )
        assertEquals("mathematicians-actuaries-and-statisticians", slugify("Mathematicians, Actuaries and Statisticians"))
        assertEquals(60, slugify("a".repeat(100)).length)
    }

    @Test
    fun promptNamesPositionAndLanguage() {
        val prompt = buildPrompt(UnitGroup("1111", "Legislators"), "no")
        assertTrue(prompt.contains("Legislators"))
        assertTrue(prompt.contains("Norwegian Bokmål"))
        assertTrue(prompt.contains("Markdown"))
    }
}
