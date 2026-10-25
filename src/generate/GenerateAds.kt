package generate

import generate.GenerateRequest
import generate.GenerateResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import prepare.Occupations
import prepare.UnitGroup
import java.io.File
import kotlin.random.Random
import kotlin.system.measureTimeMillis

@Serializable
data class GenerateRequest(val model: String, val prompt: String, val stream: Boolean = false)

@Serializable
data class GenerateResponse(val response: String = "", val done: Boolean = false, val error: String? = null)

private val LANGUAGES = listOf("en", "no", "pl")

private val LANGUAGE_NAMES = mapOf(
    "en" to "English",
    "no" to "Norwegian Bokmål",
    "pl" to "Polish",
)

private val jsonConfig = Json { ignoreUnknownKeys = true }

fun run(args: Array<String>) = runBlocking {
    val limit = args.argValue("--limit")?.toIntOrNull()
    val delayMs = args.argValue("--delay-ms")?.toLongOrNull() ?: 0L
    val ollamaUrl = System.getenv("OLLAMA_URL") ?: "http://localhost:11434"
    val model = System.getenv("BIELIK_MODEL") ?: "SpeakLeash/bielik-11b-v3.0-instruct:Q6_K"

    val occupationsFile = File("local/occupations.json")
    require(occupationsFile.exists()) { "Missing ${occupationsFile.path}, run 'prepare' first" }
    val occupations = jsonConfig.decodeFromString<Occupations>(occupationsFile.readText())

    val adsDir = File("local/ads").apply { mkdirs() }
    val units = occupations.majors.flatMap { m ->
        m.subMajors.flatMap { s -> s.minors.flatMap { it.units } }
    }
    val todo = units.filter { unit ->
        !adsDir.listFiles().orEmpty().any { it.name.startsWith("${unit.code}-") }
    }
    val selected = if (limit != null) todo.take(limit) else todo

    println("Model: $model ($ollamaUrl)")
    println("${selected.size} of ${units.size} occupations to generate (${todo.size} missing)")
    if (selected.isEmpty()) return@runBlocking

    HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 5 * 60 * 1000 }
    }.use { client ->
        selected.forEachIndexed { i, unit ->
            val lang = LANGUAGES.random()
            val file = File(adsDir, "${unit.code}-$lang-${slugify(unit.label)}.md")
            val prompt = buildPrompt(unit, lang)
            try {
                val elapsed = measureTimeMillis {
                    val response = client.post("$ollamaUrl/api/generate") {
                        contentType(ContentType.Application.Json)
                        setBody(jsonConfig.encodeToString(GenerateRequest(model, prompt)))
                    }
                    if (!response.status.isSuccess()) {
                        error("HTTP ${response.status}: ${response.bodyAsText()}")
                    }
                    val body = response.bodyAsText()
                    if (!response.status.isSuccess()) {
                        error("HTTP ${response.status}: $body")
                    }
                    val text = parseGenerateResponse(body)
                    text.error?.let { error(it) }
                    file.writeText(text.response.trim())
                }
                println("[${i + 1}/${selected.size}] ${unit.code} ($lang) ok (${elapsed}ms) -> ${file.name}")
            } catch (e: Exception) {
                println("[${i + 1}/${selected.size}] ${unit.code} ($lang) FAILED: ${e.message}")
            }
            if (delayMs > 0 && i < selected.size - 1) delay(delayMs)
        }
    }
    println("Done. Ads in ${adsDir.path}")
}

fun buildPrompt(unit: UnitGroup, lang: String): String {
    val language = LANGUAGE_NAMES.getValue(lang)
    return """
        Write a realistic job advertisement for the position: ${unit.label}.
        Write the entire advertisement in $language.
        The advertisement must be self-contained and written in Markdown,
        without any meta-commentary, explanations, or headings about the task itself.
        Include a job title, a short company description, responsibilities,
        requirements, and what the employer offers.
    """.trimIndent()
}

fun parseGenerateResponse(body: String): GenerateResponse {
    val chunks = body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { jsonConfig.decodeFromString<GenerateResponse>(it) }
        .toList()
    return when {
        chunks.size == 1 -> chunks[0]
        else -> chunks.fold(GenerateResponse()) { acc, chunk ->
            GenerateResponse(acc.response + chunk.response, chunk.done, chunk.error ?: acc.error)
        }
    }
}

fun slugify(label: String): String =
    label.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(60)
        .trim('-')

private fun Array<String>.argValue(name: String): String? =
    asList().windowed(2).firstOrNull { it[0] == name }?.get(1)
        ?: if (contains(name)) "" else null

fun main(args: Array<String>) = run(args)
