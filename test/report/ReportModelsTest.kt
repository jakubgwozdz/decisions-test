package report

import compare.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun row(
    kind: String,
    stage: String,
    ad: String,
    correct: Boolean?,
    error: String? = null,
    predictions: List<String> = emptyList(),
) = QueryResult(
    timestamp = "2026-11-02T00:00:00Z",
    ad = ad,
    lang = "en",
    expected = "1111",
    expectedMinor = "111",
    kind = kind,
    stage = stage,
    approach = "test",
    model = "m",
    durationMs = 100,
    promptTokens = 10,
    completionTokens = 5,
    predictions = predictions,
    probabilities = emptyList(),
    rawResponse = null,
    correct = correct,
    error = error,
)

class ReportModelsTest {

    @Test
    fun aggregatesPerKindAndAd() {
        val results = listOf(
            row("chat", "unit", "a.md", true),
            row("chat", "unit", "b.md", false),
            row("jev-choice", "unit", "a.md", true, predictions = listOf("1111")),
            row("jev-choice", "minor", "a.md", true),
            row("jev-choice", "unit", "b.md", null, error = "boom"),
        )
        val (stats, outcomes) = aggregate(results)

        val chat = stats.single { it.kind == "chat" }
        assertEquals(2, chat.unitTotal)
        assertEquals(1, chat.unitCorrect)
        assertEquals(0.5, chat.unitAccuracy)
        assertEquals(20, chat.promptTokens)
        assertEquals(100, chat.avgUnitMs)

        val jev = stats.single { it.kind == "jev-choice" }
        assertEquals(1, jev.errors)
        assertEquals(1, jev.minorTotal)
        assertEquals(1, jev.minorCorrect)
        assertEquals(30, jev.promptTokens)

        assertEquals(2, outcomes.size)
        val a = outcomes.single { it.ad == "a.md" }
        assertEquals(true, a.results["chat"])
        assertEquals(listOf("1111"), a.predictions["jev-choice"])
    }

    @Test
    fun reportContainsSummaryTableOutcomesAndErrors() {
        val results = listOf(
            row("chat", "unit", "a.md", false, predictions = listOf("9999")),
            row("chat", "minor", "a.md", true),
            row("jev-score", "unit", "a.md", null, error = "HTTP 500"),
        )
        val report = buildReport(listOf("results-1.jsonl"), results, "now")
        assertTrue(report.contains("| chat | m | 1 | 0/1 (0%) | 1/1 (100%) | 0 | 100 | 20 | 10 |"))
        assertTrue(report.contains("WRONG -> 9999"))
        assertTrue(report.contains("no prediction"))
        assertTrue(report.contains("## Errors (1)"))
        assertTrue(report.contains("HTTP 500"))
    }
}

class ReportRunTest {

    @Test
    fun unwrapLeavesPlainTextAlone() {
        assertEquals("*hello* world", unwrapJsonString("*hello* world"))
    }

    @Test
    fun unwrapExtractsMessageFromJsonObject() {
        assertEquals("*verdict*", unwrapJsonString("{\"message\":\"*verdict*\"}"))
    }

    @Test
    fun unwrapUnquotesJsonString() {
        assertEquals("plain", unwrapJsonString("\"plain\""))
    }
}

class FileTimestampTest {

    @Test
    fun fileTimestampIsIsoUtcSafeForFileNames() {
        val ts = compare.fileTimestamp(java.time.Instant.parse("2026-11-02T19:21:11.123Z"))
        assertEquals("2026-11-02T19-21-11Z", ts)
        assertTrue(ts.none { it == ':' || it == '/' })
    }
}
