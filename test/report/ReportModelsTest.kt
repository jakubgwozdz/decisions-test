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
        assertTrue(report.contains("| chat | m | 1 | 0/1 (0%) | 1/1 (100%) | 0 | 100 | 20 | 10 | - |"))
        assertTrue(report.contains("WRONG -> 9999"))
        assertTrue(report.contains("no prediction"))
        assertTrue(report.contains("## Errors (1)"))
        assertTrue(report.contains("HTTP 500"))
    }

    @Test
    fun costIsComputedFromModelPricing() {
        assertEquals("0.10", MODEL_PRICING.first { it.first == "gpt-6-luna" }.second.inputUsdPer1M)
        assertEquals("10.00", MODEL_PRICING.first { it.first == "gpt-6.1-sol" }.second.outputUsdPer1M)
        assertEquals("0", MODEL_PRICING.first { it.first == "jev-1.14.0" }.second.outputUsdPer1M)

        // gpt-6-luna: $0.10/1M in, $0.50/1M out
        assertEquals(0, costUsd("gpt-6-luna", 1_000_000, 1_000_000)!!.compareTo(java.math.BigDecimal("0.60")))
        // gpt-6.1-sol: $2.00/1M in, $10.00/1M out
        assertEquals(0, costUsd("gpt-6.1-sol", 1_000_000, 1_000_000)!!.compareTo(java.math.BigDecimal("12.00")))
        // jev-1.14.0: $0.04/1M in, free output; prefix like "typesafe/" must still match
        assertEquals(0, costUsd("typesafe/jev-1.14.0", 1_000_000, 1_000_000)!!.compareTo(java.math.BigDecimal("0.04")))
        // unknown model -> no cost
        assertEquals(null, costUsd("m", 10, 5))

        assertEquals("$0.6", formatCost(costUsd("gpt-6-luna", 1_000_000, 1_000_000)))
        assertEquals("$0.399666", formatCost(costUsd("gpt-6.1-sol", 195_033, 960)))
        assertEquals("$0", formatCost(java.math.BigDecimal.ZERO))
        assertEquals("-", formatCost(null))
    }

    @Test
    fun reportShowsCostPerKindAndTotal() {
        val results = listOf(
            row("chat", "unit", "a.md", true).copy(model = "gpt-6-luna", promptTokens = 1_000_000, completionTokens = 1_000_000), // $0.6
            row("jev-choice", "unit", "a.md", true).copy(model = "typesafe/jev-1.14.0", promptTokens = 1_000_000, completionTokens = 0), // $0.04
        )
        val report = buildReport(listOf("results-1.jsonl"), results, "now")
        assertTrue(report.contains("| chat | gpt-6-luna | 1 | 1/1 (100%) | 0/0 (0%) | 0 | 100 | 1000000 | 1000000 | $0.6 |"))
        assertTrue(report.contains("| jev-choice | typesafe/jev-1.14.0 | 1 | 1/1 (100%) | 0/0 (0%) | 0 | 100 | 1000000 | 0 | $0.04 |"))
        assertTrue(report.contains("- Total cost: $0.64")) // 0.60 + 0.04
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

    @Test
    fun unwrapFallsBackToRawForEmptyJsonObject() {
        // model forced into json_object mode but asked for plain text may return {"": ""}
        assertEquals("{\"\": \"\"}", unwrapJsonString("{\"\": \"\"}"))
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
