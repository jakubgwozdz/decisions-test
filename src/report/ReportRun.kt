package report

import compare.ChatMessage
import compare.QueryResult
import compare.callChat
import compare.jsonConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import compare.fileTimestamp
import java.time.Instant

private const val REPORT_TEXT_LIMIT = 60_000

private fun analysisPrompt(report: String): List<ChatMessage> = listOf(
    ChatMessage(
        "system",
        """
        You are a benchmark analyst. You receive a Markdown report of an occupation-classification
        benchmark comparing several model kinds (chat, openai-predicate/choice/score,
        jev-predicate/choice/score) on ISCO-08 job-ad classification: per-kind accuracy, latency,
        token usage, per-ad outcomes and errors.
        Write the conclusions in ENGLISH as a trimmed Markdown message ready to paste into Slack:
        use Slack mrkdwn (*bold*, `code`, bullet lists with "-"), NO ATX headings, no tables,
        no links. Keep it under 30 lines. Structure: one-line overall verdict, key numbers per
        kind (unit accuracy, avg latency, tokens, errors), then 3-5 bullet conclusions and
        suggested next steps. Respond ONLY with the Slack-ready message, no preamble, no fences.
        """.trimIndent(),
    ),
    ChatMessage("user", "REPORT:\n$report"),
)

fun run(args: Array<String>) = runBlocking {
    val compareDir = File(args.argValue("--dir") ?: "local/compare")
    val outDir = File(args.argValue("--out") ?: "local/report").apply { mkdirs() }
    val onlyFile = args.argValue("--file")?.let { File(it) }
    val skipChat = args.contains("--no-chat")

    val candidates = compareDir.listFiles().orEmpty()
        .filter { it.name.startsWith("results-") && it.name.endsWith(".jsonl") }
    val files = when {
        args.contains("--all") -> candidates.sortedBy { it.name }
        onlyFile != null -> listOf(onlyFile)
        else -> listOfNotNull(candidates.maxByOrNull { it.lastModified() })
    }
    require(files.isNotEmpty()) { "No results-*.jsonl in ${compareDir.path} (run 'compare' first)" }

    val results = files.flatMap { f ->
        f.readLines().filter { it.isNotBlank() }.map { line ->
            jsonConfig.decodeFromString(QueryResult.serializer(), line)
        }
    }

    val ts = fileTimestamp()
    val reportFile = File(outDir, "report-$ts.md")
    val report = buildReport(files.map { it.name }, results, Instant.now().toString())
    reportFile.writeText(report)
    println("Report: ${results.size} rows from ${files.size} file(s) -> ${reportFile.path}")

    if (skipChat) {
        println("Skipping chat analysis (--no-chat)")
        return@runBlocking
    }

    val baseUrl = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com"
    val model = System.getenv("CHAT_MODEL") ?: "gpt-6.1-sol"
    val apiKeyFile = File("local/secrets/OPENAI_API_KEY.txt")
    require(apiKeyFile.exists()) { "Missing ${apiKeyFile.path}" }
    val apiKey = apiKeyFile.readText().trim()

    println("Analyzing report with $model ($baseUrl) ...")
    HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 2 * 60 * 1000 }
    }.use { client ->
        val call = callChat(client, baseUrl, apiKey, model, analysisPrompt(report.take(REPORT_TEXT_LIMIT)))
        val conclusions = unwrapJsonString(call.rawResponse?.trim().orEmpty())
        require(conclusions.isNotEmpty()) { "Empty analysis response" }
        val conclusionsFile = File(outDir, "conclusions-$ts.md")
        conclusionsFile.writeText(conclusions + "\n")
        println("Conclusions -> ${conclusionsFile.path}")
        println()
        println(conclusions)
    }
}

internal fun unwrapJsonString(raw: String): String {
    if (!raw.startsWith("{") && !raw.startsWith("\"")) return raw
    return runCatching {
        when (val el = jsonConfig.parseToJsonElement(raw)) {
            is JsonPrimitive -> el.content
            is JsonObject -> listOf("message", "conclusions", "text", "content")
                .firstNotNullOfOrNull { (el[it] as? JsonPrimitive)?.content }
                ?: raw
            else -> raw
        }
    }.getOrDefault(raw)
}

private fun Array<String>.argValue(name: String): String? =
    asList().windowed(2).firstOrNull { it[0] == name }?.get(1)
        ?: if (contains(name)) "" else null
