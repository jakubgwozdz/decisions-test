package compare

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import prepare.Occupations
import java.io.File
import java.time.Instant
import kotlin.random.Random

private const val AD_TEXT_LIMIT = 12_000

private const val TOP_N = 3

private const val CHOICE_PROBABILITY_THRESHOLD = 0.3

private fun File.appendLine(line: String) = appendText(line + "\n")

private fun chatPrompt(units: List<FlatUnit>, minors: Map<String, String>, adText: String): List<ChatMessage> {
    val minorByCode = minors
    val list = units.joinToString("\n") { unit ->
        val minorLabel = minorByCode[unit.minorCode] ?: "?"
        "${unit.code}\t${unit.label}\t[minor ${unit.minorCode}: $minorLabel]"
    }
    return listOf(
        ChatMessage(
            "system",
            """
            You are an occupation classifier. You are given a list of occupations (ISCO-08 unit groups),
            one per line, in the format: CODE<TAB>TITLE<TAB>[minor CODE: MINOR TITLE].
            Each occupation belongs to a minor group, shown in square brackets.
            You will receive a job advertisement text.
            Your task: choose 1 to 3 occupation codes that best match the advertised position.
            Prefer codes whose minor group context fits the advertisement.
            Respond ONLY with a JSON object of the form
            {"units": ["1234", ...], "reasoning": "one sentence why"}.
            The units must be 1-3 codes taken exactly from the list. No other keys.
            """.trimIndent(),
        ),
        ChatMessage("user", "OCCUPATIONS:\n$list\n\nJOB ADVERTISEMENT:\n$adText"),
    )
}

private fun minorDescription(minor: MinorInfo): String {
    val examples = minor.unitLabels.take(5).joinToString(", ")
    val more = if (minor.unitLabels.size > 5) " and ${minor.unitLabels.size - 5} more" else ""
    return "${minor.label} (includes: $examples$more)"
}

private fun predicateQuestions(candidates: List<Pair<String, String>>, kind: String): List<DecisionQuestion> =
    candidates.map { (code, label) ->
        DecisionQuestion(
            type = "predicate",
            name = code,
            instructions = "Does the job advertisement describe a position that is a $kind: $label ($code)? Answer with the probability that it is.",
        )
    }

private fun choiceQuestion(candidates: List<Pair<String, String>>, kind: String, name: String): DecisionQuestion =
    DecisionQuestion(
        type = "choice",
        name = name,
        instructions = "Which $kind best matches the position described in the job advertisement? Choose exactly one option.",
        choices = candidates.map { (code, label) ->
            DecisionChoiceOption(value = code, description = label)
        },
    )

const val SCORE_MAX_LEVELS = 10

const val SCORE_MIN_LEVELS = 2

fun scoreQuestions(candidates: List<Pair<String, String>>, kind: String): List<DecisionQuestion> {
    val batches = mutableListOf<List<Pair<String, String>>>()
    var i = 0
    while (i < candidates.size) {
        val remaining = candidates.size - i
        val size = when {
            remaining <= SCORE_MAX_LEVELS -> remaining
            remaining - SCORE_MAX_LEVELS < SCORE_MIN_LEVELS -> SCORE_MAX_LEVELS - 1
            else -> SCORE_MAX_LEVELS
        }
        batches.add(candidates.subList(i, i + size))
        i += size
    }
    return batches.mapIndexed { batchIdx, batch ->
        DecisionQuestion(
            type = "score",
            name = "score_$batchIdx",
            instructions = "Rate how well the job advertisement matches EACH of these $kind options. " +
                "For each option, estimate the probability that the advertised position belongs to it. " +
                "Options: " + batch.joinToString("; ") { "${it.first} = ${it.second}" } + ".",
            levels = batch.map { (code, label) ->
                DecisionLevel(label = code, description = label)
            },
        )
    }
}

enum class Approach(val questionType: String) {
    PREDICATE("predicate"),
    CHOICE("choice"),
    SCORE("score");

    companion object {
        fun fromName(name: String): Approach? = entries.firstOrNull { it.questionType == name.lowercase() }
    }
}

enum class DecisionProvider { OPENAI, OPPER }

sealed interface BenchmarkKind

data object ChatKind : BenchmarkKind

data class DecisionKind(val name: String, val provider: DecisionProvider, val approach: Approach) : BenchmarkKind

fun parseKinds(spec: String): List<BenchmarkKind> =
    spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { token ->
        if (token == "chat") {
            ChatKind
        } else {
            val dash = token.indexOf('-')
            require(dash > 0) {
                "Unknown kind '$token' (expected: chat | openai-<predicate|choice|score> | jev-<predicate|choice|score>)"
            }
            val provider = when (token.substring(0, dash)) {
                "openai" -> DecisionProvider.OPENAI
                "jev" -> DecisionProvider.OPPER
                else -> throw IllegalArgumentException("Unknown provider in kind '$token' (expected openai or jev)")
            }
            val approach = Approach.fromName(token.substring(dash + 1))
                ?: throw IllegalArgumentException("Unknown approach in kind '$token' (expected predicate, choice or score)")
            DecisionKind(token, provider, approach)
        }
    }

internal data class ApiCall(
    val predictions: List<String>,
    val probabilities: List<DecisionProbability>,
    val rawResponse: String?,
    val durationMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
)

internal suspend fun callChat(
    client: HttpClient,
    baseUrl: String,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
): ApiCall {
    val startedAt = System.currentTimeMillis()
    val response = client.post("$baseUrl/v1/chat/completions") {
        bearerAuth(apiKey)
        contentType(ContentType.Application.Json)
        setBody(jsonConfig.encodeToString(ChatCompletionRequest(model, messages)))
    }
    if (!response.status.isSuccess()) {
        error("HTTP ${response.status}: ${response.bodyAsText()}")
    }
    val body = jsonConfig.decodeFromString<ChatCompletionResponse>(response.bodyAsText())
    body.error?.let { error(it.message) }
    val raw = body.choices.firstOrNull()?.message?.content
        ?: error("Empty choices in response")
    return ApiCall(
        predictions = parsePredictions(raw, "units"),
        probabilities = emptyList(),
        rawResponse = raw,
        durationMs = System.currentTimeMillis() - startedAt,
        promptTokens = body.usage?.effectivePromptTokens,
        completionTokens = body.usage?.effectiveCompletionTokens,
    )
}

private suspend fun callDecisions(
    client: HttpClient,
    endpoint: String,
    apiKey: String,
    model: String,
    adText: String,
    candidates: List<Pair<String, String>>,
    kind: String,
    approach: String,
    questionName: String = "classify",
): ApiCall {
    val startedAt = System.currentTimeMillis()
    val questions = when (approach) {
        "predicate" -> predicateQuestions(candidates, kind)
        "choice" -> listOf(choiceQuestion(candidates, kind, questionName))
        "score" -> scoreQuestions(candidates, kind)
        else -> error("Unknown approach: $approach")
    }
    val response = client.post(endpoint) {
        bearerAuth(apiKey)
        contentType(ContentType.Application.Json)
        setBody(jsonConfig.encodeToString(DecisionRequest(
            model = model,
            input = adText,
            questions = questions,
        )))
    }
    val rawBody = response.bodyAsText()
    if (!response.status.isSuccess()) {
        error("HTTP ${response.status}: $rawBody")
    }
    val body = jsonConfig.decodeFromString<DecisionResponse>(rawBody)
    body.error?.let { error(it.message) }
    val probs: List<DecisionProbability> = when (approach) {
        "predicate" -> body.answers.mapNotNull { a ->
            when {
                a.type == "refusal" -> null
                a.probability == null -> null
                else -> DecisionProbability(a.name, a.probability)
            }
        }
        "choice" -> {
            val answer = body.answers.firstOrNull { it.type == "choice" }
                ?: error("No choice answer in response")
            answer.choiceProbabilities().ifEmpty {
                answer.choice?.let { listOf(DecisionProbability(it, answer.confidence ?: 1.0)) } ?: emptyList()
            }
        }
        "score" -> body.answers.filter { it.type == "score" }.flatMap { answer ->
            val answerIdx = answer.name.removePrefix("score_").toIntOrNull() ?: 0
            val batchStart = answerIdx * SCORE_MAX_LEVELS
            answer.scoreProbabilities().mapNotNull { p ->
                val candidate = candidates.getOrNull(batchStart + p.value) ?: return@mapNotNull null
                DecisionProbability(candidate.first, p.probability, candidate.second)
            }
        }
        else -> emptyList()
    }
    val top = probs.sortedByDescending { it.probability }
        .filter { it.probability >= CHOICE_PROBABILITY_THRESHOLD }
        .take(TOP_N)
    return ApiCall(
        predictions = top.map { it.value },
        probabilities = probs.sortedByDescending { it.probability },
        rawResponse = rawBody,
        durationMs = System.currentTimeMillis() - startedAt,
        promptTokens = body.usage?.effectivePromptTokens,
        completionTokens = body.usage?.effectiveCompletionTokens,
    )
}

private fun result(
    ad: String,
    lang: String,
    expected: String,
    expectedMinor: String?,
    kind: String,
    stage: String,
    approach: String,
    model: String,
    call: ApiCall?,
    correct: Boolean?,
    error: String?,
): QueryResult = QueryResult(
    timestamp = Instant.now().toString(),
    ad = ad,
    lang = lang,
    expected = expected,
    expectedMinor = expectedMinor,
    kind = kind,
    stage = stage,
    approach = approach,
    model = model,
    durationMs = call?.durationMs ?: 0,
    promptTokens = call?.promptTokens,
    completionTokens = call?.completionTokens,
    predictions = call?.predictions ?: emptyList(),
    probabilities = call?.probabilities ?: emptyList(),
    rawResponse = call?.rawResponse,
    correct = correct,
    error = error,
)

fun run(args: Array<String>) = runBlocking {
    val adsCount = args.argValue("--ads")?.toIntOrNull() ?: 10
    val seed = args.argValue("--seed")?.toLongOrNull()
    val delayMs = args.argValue("--delay-ms")?.toLongOrNull() ?: 1000L
    val kinds = parseKinds(System.getenv("COMPARE_KINDS") ?: "chat,openai-predicate,openai-choice,openai-score,jev-predicate,jev-choice,jev-score")
    val baseUrl = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com"
    val chatModel = System.getenv("CHAT_MODEL") ?: "gpt-6.1-sol"
    val openaiModel = System.getenv("OPENAI_MODEL") ?: "gpt-6-luna"
    val opperBaseUrl = System.getenv("OPPER_BASE_URL") ?: "https://api.opper.ai/v3/compat"
    val jevModel = System.getenv("JEV_MODEL") ?: "typesafe/jev-1.14.0"

    val apiKeyFile = File("local/secrets/OPENAI_API_KEY.txt")
    require(apiKeyFile.exists()) { "Missing ${apiKeyFile.path}" }
    val apiKey = apiKeyFile.readText().trim()

    val needsOpper = kinds.filterIsInstance<DecisionKind>().any { it.provider == DecisionProvider.OPPER }
    val opperApiKey = if (needsOpper) {
        val opperKeyFile = File("local/secrets/OPPER_API_KEY.txt")
        require(opperKeyFile.exists()) { "Missing ${opperKeyFile.path} (needed for jev-* kinds)" }
        opperKeyFile.readText().trim()
    } else null

    val occupations = jsonConfig.decodeFromString<Occupations>(File("local/occupations.json").readText())
    val units = flattenUnits(occupations)
    val minors = minorsOf(occupations)
    val minorsByCode = minors.associate { it.code to it.label }
    val unitsByCode = units.associateBy { it.code }

    val adsDir = File("local/ads")
    val adFiles = adsDir.listFiles().orEmpty()
        .mapNotNull { f -> parseAdFileName(f.name)?.let { it to f } }
    require(adFiles.isNotEmpty()) { "No ads in ${adsDir.path}, run 'generate' first" }

    val rng = if (seed != null) Random(seed) else Random.Default
    val selected = List(adsCount) { adFiles[rng.nextInt(adFiles.size)] }.distinct()

    val compareDir = File("local/compare").apply { mkdirs() }
    val resultsFile = File(compareDir, "results-${fileTimestamp()}.jsonl")

    println("Kinds: ${kinds.joinToString { (it as? DecisionKind)?.name ?: "chat" }}")
    println("Chat: $chatModel ($baseUrl) | Decisions: $openaiModel | Jev: $jevModel ($opperBaseUrl)")
    println("Comparing on ${selected.size} ads (of ${adFiles.size} available), results -> ${resultsFile.path}")

    HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 2 * 60 * 1000 }
    }.use { client ->
        selected.forEachIndexed { i, (info, file) ->
            val adText = file.readText().take(AD_TEXT_LIMIT)
            val expectedMinor = unitsByCode[info.code]?.minorCode
            val base = result(
                ad = file.name, lang = info.lang, expected = info.code,
                expectedMinor = expectedMinor, kind = "", stage = "", approach = "", model = "",
                call = null, correct = null, error = null,
            )

            println("[${i + 1}/${selected.size}] ${file.name} (expected ${info.code})")

            for (kind in kinds) {
                when (kind) {
                    ChatKind -> {
                        try {
                            runChatKind(client, baseUrl, apiKey, chatModel, adText, info.code, units, minorsByCode, base, resultsFile)
                        } catch (e: Exception) {
                            resultsFile.appendLine(jsonConfig.encodeToString(base.copy(
                                kind = "chat", stage = "unit", approach = "chat", model = chatModel, error = e.message,
                            )))
                            println("  chat         FAILED: ${e.message}")
                        }
                    }
                    is DecisionKind -> {
                        val (endpoint, key, model) = when (kind.provider) {
                            DecisionProvider.OPENAI -> Triple("$baseUrl/v1/decisions", apiKey, openaiModel)
                            DecisionProvider.OPPER -> Triple("$opperBaseUrl/decisions", requireNotNull(opperApiKey), jevModel)
                        }
                        try {
                            runDecisionKind(client, kind, endpoint, key, model, adText,
                                info.code, expectedMinor, units, minors, minorsByCode, base, resultsFile)
                        } catch (e: Exception) {
                            resultsFile.appendLine(jsonConfig.encodeToString(base.copy(
                                kind = kind.name, stage = "minor", approach = kind.approach.questionType,
                                model = model, error = e.message,
                            )))
                            println("  ${kind.name} FAILED: ${e.message}")
                        }
                    }
                }
            }

            if (delayMs > 0 && i < selected.size - 1) delay(delayMs)
        }
    }
    println("Done. Results in ${resultsFile.path}")
}

private suspend fun runChatKind(
    client: HttpClient,
    baseUrl: String,
    apiKey: String,
    model: String,
    adText: String,
    adCode: String,
    units: List<FlatUnit>,
    minorsByCode: Map<String, String>,
    base: QueryResult,
    resultsFile: File,
) {
    val chatCall = callChat(client, baseUrl, apiKey, model, chatPrompt(units, minorsByCode, adText))
    val chatCorrect = if (chatCall.predictions.isEmpty()) null else adCode in chatCall.predictions
    resultsFile.appendLine(jsonConfig.encodeToString(base.copy(
        kind = "chat", stage = "unit", approach = "chat", model = model,
        durationMs = chatCall.durationMs, promptTokens = chatCall.promptTokens,
        completionTokens = chatCall.completionTokens, predictions = chatCall.predictions,
        probabilities = chatCall.probabilities,
        rawResponse = chatCall.rawResponse, correct = chatCorrect, error = null,
    )))
    println("  chat         ${chatCall.predictions} correct=$chatCorrect (${chatCall.durationMs}ms)")
}

private suspend fun runDecisionKind(
    client: HttpClient,
    kind: DecisionKind,
    endpoint: String,
    apiKey: String,
    model: String,
    adText: String,
    adCode: String,
    expectedMinor: String?,
    units: List<FlatUnit>,
    minors: List<MinorInfo>,
    minorsByCode: Map<String, String>,
    base: QueryResult,
    resultsFile: File,
) {
    val approach = kind.approach.questionType
    val minorCandidates = minors.map { it.code to minorDescription(it) }
    val minorCall = callDecisions(client, endpoint, apiKey, model, adText,
        minorCandidates, "ISCO-08 minor group", approach, questionName = "minor")
    val minorCorrect = if (minorCall.predictions.isEmpty() || expectedMinor == null) null
        else expectedMinor in minorCall.predictions
    resultsFile.appendLine(jsonConfig.encodeToString(base.copy(
        kind = kind.name, stage = "minor", approach = approach, model = model,
        durationMs = minorCall.durationMs, promptTokens = minorCall.promptTokens,
        completionTokens = minorCall.completionTokens, predictions = minorCall.predictions,
        probabilities = minorCall.probabilities,
        rawResponse = minorCall.rawResponse, correct = minorCorrect, error = null,
    )))
    println("  ${kind.name} minors ${minorCall.predictions} correct=$minorCorrect (${minorCall.durationMs}ms)")

    if (minorCall.predictions.isEmpty()) {
        resultsFile.appendLine(jsonConfig.encodeToString(base.copy(
            kind = kind.name, stage = "unit", approach = approach, model = model,
            error = "No minors above threshold, skipping unit stage",
        )))
        println("  ${kind.name} unit stage skipped (no minors above threshold)")
        return
    }

    val candidateUnits = units.filter { it.minorCode in minorCall.predictions }
    if (candidateUnits.size == 1 && kind.approach != Approach.PREDICATE) {
        val only = candidateUnits.single()
        val correct = adCode == only.code
        resultsFile.appendLine(jsonConfig.encodeToString(base.copy(
            kind = kind.name, stage = "unit", approach = approach, model = model,
            predictions = listOf(only.code),
            probabilities = listOf(DecisionProbability(only.code, 1.0, only.label)),
            rawResponse = null, correct = correct, error = null,
        )))
        println("  ${kind.name} unit single candidate ${only.code} correct=$correct (no API call)")
        return
    }

    val unitInput = if (approach != "predicate" && expectedMinor != null) {
        "$adText\n\nCONTEXT: This advertisement was previously classified as ISCO-08 minor group " +
            "$expectedMinor: ${minorsByCode[expectedMinor]}. Choose the exact occupation within this context."
    } else adText
    val unitCall = callDecisions(client, endpoint, apiKey, model, unitInput,
        candidateUnits.map { it.code to it.label }, "ISCO-08 occupation (unit group)", approach, questionName = "unit")
    val unitCorrect = if (unitCall.predictions.isEmpty()) null else adCode in unitCall.predictions
    resultsFile.appendLine(jsonConfig.encodeToString(base.copy(
        kind = kind.name, stage = "unit", approach = approach, model = model,
        durationMs = unitCall.durationMs, promptTokens = unitCall.promptTokens,
        completionTokens = unitCall.completionTokens, predictions = unitCall.predictions,
        probabilities = unitCall.probabilities,
        rawResponse = unitCall.rawResponse, correct = unitCorrect, error = null,
    )))
    println("  ${kind.name} units ${unitCall.predictions} correct=$unitCorrect (${unitCall.durationMs}ms)")
}

private fun Array<String>.argValue(name: String): String? =
    asList().windowed(2).firstOrNull { it[0] == name }?.get(1)
        ?: if (contains(name)) "" else null
