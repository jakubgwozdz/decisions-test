package compare

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import prepare.MajorGroup
import prepare.MinorGroup
import prepare.Occupations
import prepare.SubMajorGroup
import prepare.UnitGroup

class CompareModelsTest {

    private val occupations = Occupations(
        listOf(
            MajorGroup(
                "1", "Managers",
                listOf(
                    SubMajorGroup(
                        "11", "Chief Executives",
                        listOf(
                            MinorGroup(
                                "111", "Legislators and Senior Officials",
                                listOf(UnitGroup("1111", "Legislators"), UnitGroup("1112", "Senior Government Officials")),
                            ),
                            MinorGroup(
                                "112", "Managing Directors",
                                listOf(UnitGroup("1120", "Managing Directors and Chief Executives")),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun flattensUnitsWithMinorCodes() {
        val units = flattenUnits(occupations)
        assertEquals(3, units.size)
        assertEquals(FlatUnit("1111", "Legislators", "111"), units[0])
        assertEquals(FlatUnit("1120", "Managing Directors and Chief Executives", "112"), units[2])
    }

    @Test
    fun listsMinors() {
        val minors = minorsOf(occupations)
        assertEquals(
            listOf(
                MinorInfo("111", "Legislators and Senior Officials", listOf("Legislators", "Senior Government Officials")),
                MinorInfo("112", "Managing Directors", listOf("Managing Directors and Chief Executives")),
            ),
            minors,
        )
    }

    @Test
    fun parsesAdFileName() {
        val ad = parseAdFileName("1114-en-senior-officials-of-special-interest-organizations.md")
        assertEquals(AdInfo("1114", "en", "senior-officials-of-special-interest-organizations"), ad)
        assertNull(parseAdFileName("README.md"))
        assertNull(parseAdFileName("111-en-too-short.md"))
    }

    @Test
    fun parsesPredictionsFromJson() {
        assertEquals(listOf("1111", "1112"), parsePredictions("""{"units": ["1111", "1112"]}""", "units"))
        assertEquals(emptyList<String>(), parsePredictions("""{"units": []}""", "units"))
        assertEquals(emptyList<String>(), parsePredictions("""{"other": 1}""", "units"))
    }

    @Test
    fun decisionAnswerParsing() {
        val response = jsonConfig.decodeFromString<DecisionResponse>("""
            {"answers": [
                {"type": "predicate", "name": "111", "probability": 0.92},
                {"type": "predicate", "name": "112", "probability": 0.03},
                {"type": "refusal", "name": "113"}
            ]}
        """.trimIndent())
        val probs = response.answers.mapNotNull { a ->
            if (a.type == "predicate" && a.probability != null) DecisionProbability(a.name, a.probability) else null
        }
        assertEquals(listOf(DecisionProbability("111", 0.92), DecisionProbability("112", 0.03)), probs)
    }

    @Test
    fun parsesChoiceAnswerWithProbabilities() {
        val response = jsonConfig.decodeFromString<DecisionResponse>("""
            {"answers": [{
                "type": "choice", "name": "minor", "choice": "241",
                "confidence": 0.87,
                "probabilities": [
                    {"value": "241", "probability": 0.87},
                    {"value": "251", "probability": 0.1}
                ]
            }]}
        """.trimIndent())
        val answer = response.answers[0]
        assertEquals("241", answer.choice)
        assertEquals(0.87, answer.confidence)
        assertEquals(2, answer.choiceProbabilities().size)
        assertEquals(DecisionProbability("241", 0.87), answer.choiceProbabilities()[0])
    }

    @Test
    fun scoreBatchingNeverCreatesTinyBatch() {
        val candidates = (1..21).map { "$it" to "Label $it" }
        val questions = scoreQuestions(candidates, "test")
        assertEquals(3, questions.size)
        assertEquals(listOf(10, 9, 2), questions.map { it.levels!!.size })
        assertEquals(21, questions.sumOf { it.levels!!.size })
        assertTrue(questions.all { it.levels!!.size in 2..10 })
    }

    @Test
    fun parsesKindSpec() {
        val kinds = parseKinds("chat, openai-choice, openai-score, jev-choice")
        assertEquals(
            listOf(
                ChatKind,
                DecisionKind("openai-choice", DecisionProvider.OPENAI, Approach.CHOICE),
                DecisionKind("openai-score", DecisionProvider.OPENAI, Approach.SCORE),
                DecisionKind("jev-choice", DecisionProvider.OPPER, Approach.CHOICE),
            ),
            kinds,
        )
    }

    @Test
    fun defaultKindSpecIsAllKinds() {
        val kinds = parseKinds("chat,openai-predicate,openai-choice,openai-score,jev-predicate,jev-choice,jev-score")
        assertEquals(7, kinds.size)
        assertEquals(1, kinds.count { it == ChatKind })
        assertEquals(3, kinds.count { (it as? DecisionKind)?.provider == DecisionProvider.OPENAI })
        assertEquals(3, kinds.count { (it as? DecisionKind)?.provider == DecisionProvider.OPPER })
    }

    @Test
    fun rejectsBadKindSpec() {
        assertFailsWith<IllegalArgumentException> { parseKinds("bogus-x") }
        assertFailsWith<IllegalArgumentException> { parseKinds("openai-quantum") }
        assertFailsWith<IllegalArgumentException> { parseKinds("wat-choice") }
    }

    @Test
    fun scoreBatchingSmallInput() {
        val questions = scoreQuestions(listOf("1" to "A", "2" to "B", "3" to "C"), "test")
        assertEquals(1, questions.size)
        assertEquals(3, questions[0].levels!!.size)
    }
}