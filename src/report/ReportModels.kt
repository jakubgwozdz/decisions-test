package report

import compare.QueryResult

data class KindStats(
    val kind: String,
    val model: String,
    val ads: Int,
    val unitCorrect: Int,
    val unitTotal: Int,
    val minorCorrect: Int,
    val minorTotal: Int,
    val errors: Int,
    val avgUnitMs: Long,
    val promptTokens: Long,
    val completionTokens: Long,
) {
    val unitAccuracy: Double get() = if (unitTotal == 0) 0.0 else unitCorrect.toDouble() / unitTotal
    val minorAccuracy: Double get() = if (minorTotal == 0) 0.0 else minorCorrect.toDouble() / minorTotal
}

data class AdOutcome(
    val ad: String,
    val lang: String,
    val expected: String,
    val results: Map<String, Boolean?>, // kind -> correct (null = no prediction / error)
    val predictions: Map<String, List<String>>, // kind -> predicted codes
)

fun aggregate(results: List<QueryResult>): Pair<List<KindStats>, List<AdOutcome>> {
    val byKind = results.groupBy { it.kind }
    val stats = byKind.map { (kind, rows) ->
        val unitRows = rows.filter { it.stage == "unit" }
        val minorRows = rows.filter { it.stage == "minor" }
        KindStats(
            kind = kind,
            model = rows.firstOrNull()?.model ?: "",
            ads = unitRows.map { it.ad }.distinct().size,
            unitCorrect = unitRows.count { it.correct == true },
            unitTotal = unitRows.count { it.correct != null },
            minorCorrect = minorRows.count { it.correct == true },
            minorTotal = minorRows.count { it.correct != null },
            errors = rows.count { it.error != null },
            avgUnitMs = if (unitRows.isEmpty()) 0 else unitRows.map { it.durationMs }.average().toLong(),
            promptTokens = rows.sumOf { (it.promptTokens ?: 0).toLong() },
            completionTokens = rows.sumOf { (it.completionTokens ?: 0).toLong() },
        )
    }.sortedWith(compareByDescending<KindStats> { it.unitAccuracy }.thenBy { it.kind })

    val byAd = results.filter { it.stage == "unit" }.groupBy { it.ad }
    val outcomes = byAd.map { (ad, rows) ->
        AdOutcome(
            ad = ad,
            lang = rows.first().lang,
            expected = rows.first().expected,
            results = rows.associate { it.kind to it.correct },
            predictions = rows.associate { it.kind to it.predictions },
        )
    }.sortedBy { it.ad }
    return stats to outcomes
}

fun buildReport(sourceFiles: List<String>, results: List<QueryResult>, generatedAt: String): String {
    val (stats, outcomes) = aggregate(results)
    val errors = results.filter { it.error != null }
    val sb = StringBuilder()
    sb.appendLine("# Benchmark report")
    sb.appendLine()
    sb.appendLine("- Generated: $generatedAt")
    sb.appendLine("- Source files (${sourceFiles.size}): ${sourceFiles.joinToString(", ")}")
    sb.appendLine("- Total query rows: ${results.size}, ads: ${outcomes.size}")
    sb.appendLine()
    sb.appendLine("## Summary per kind (unit stage)")
    sb.appendLine()
    sb.appendLine("| Kind | Model | Ads | Unit acc | Minor acc | Errors | Avg unit ms | Prompt tok | Completion tok |")
    sb.appendLine("|---|---|---|---|---|---|---|---|---|")
    for (s in stats) {
        sb.appendLine(
            "| ${s.kind} | ${s.model} | ${s.ads} | ${s.unitCorrect}/${s.unitTotal} (${pct(s.unitAccuracy)}) " +
                "| ${s.minorCorrect}/${s.minorTotal} (${pct(s.minorAccuracy)}) | ${s.errors} " +
                "| ${s.avgUnitMs} | ${s.promptTokens} | ${s.completionTokens} |"
        )
    }
    sb.appendLine()
    sb.appendLine("## Per-ad outcomes (unit stage)")
    sb.appendLine()
    val kinds = stats.map { it.kind }
    sb.appendLine("| Ad | Lang | Expected | " + kinds.joinToString(" | ") + " |")
    sb.appendLine("|---|---|---|" + "---|".repeat(kinds.size))
    for (o in outcomes) {
        val cells = kinds.joinToString(" | ") { k ->
            when (o.results[k]) {
                true -> "correct"
                false -> "WRONG -> ${o.predictions[k]?.joinToString(",") ?: "?"}"
                null -> "no prediction"
            }
        }
        sb.appendLine("| ${o.ad} | ${o.lang} | ${o.expected} | $cells |")
    }
    sb.appendLine()
    if (errors.isNotEmpty()) {
        sb.appendLine("## Errors (${errors.size})")
        sb.appendLine()
        for (e in errors) {
            sb.appendLine("- `${e.ad}` kind=${e.kind} stage=${e.stage}: ${e.error}")
        }
        sb.appendLine()
    }
    return sb.toString()
}

private fun pct(v: Double): String {
    val p = v * 100
    return if (p % 1.0 == 0.0) "${p.toLong()}%" else "%.1f%%".format(p)
}
