package compare

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import prepare.Occupations
@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ResponseFormat(val type: String)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val response_format: ResponseFormat = ResponseFormat("json_object"),
)

@Serializable
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val input_tokens: Int? = null,
    val output_tokens: Int? = null,
) {
    val effectivePromptTokens: Int get() = if (prompt_tokens > 0) prompt_tokens else input_tokens ?: 0
    val effectiveCompletionTokens: Int get() = if (completion_tokens > 0) completion_tokens else output_tokens ?: 0
}

@Serializable
data class Choice(val message: ChatMessage)

@Serializable
data class OpenAiError(val message: String = "")

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    val error: OpenAiError? = null,
)

@Serializable
data class DecisionChoiceOption(val value: String, val description: String)

@Serializable
data class DecisionLevel(val label: String, val description: String)

@Serializable
data class DecisionQuestion(
    val type: String,
    val name: String,
    val instructions: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val choices: List<DecisionChoiceOption>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val levels: List<DecisionLevel>? = null,
)

@Serializable
data class DecisionRequest(val model: String, val input: String, val questions: List<DecisionQuestion>)

@Serializable
data class DecisionProbability(val value: String, val probability: Double, val label: String? = null)

@Serializable
data class DecisionScoreProbability(val value: Int, val label: String? = null, val probability: Double)

@Serializable
data class DecisionAnswer(
    val type: String,
    val name: String,
    val probability: Double? = null,
    val choice: String? = null,
    val score: Double? = null,
    val confidence: Double? = null,
    val probabilities: List<JsonElement> = emptyList(),
    val refusal: String? = null,
)

fun DecisionAnswer.choiceProbabilities(): List<DecisionProbability> =
    probabilities.mapNotNull { el ->
        val obj = el.jsonObject
        val value = obj["value"]?.let { (it as? JsonPrimitive)?.content } ?: return@mapNotNull null
        val probability = obj["probability"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: return@mapNotNull null
        DecisionProbability(value, probability)
    }

fun DecisionAnswer.scoreProbabilities(): List<DecisionScoreProbability> =
    probabilities.mapNotNull { el ->
        val obj = el.jsonObject
        val value = obj["value"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: return@mapNotNull null
        val probability = obj["probability"]?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() } ?: return@mapNotNull null
        DecisionScoreProbability(value, (obj["label"] as? JsonPrimitive)?.content, probability)
    }

@Serializable
data class DecisionResponse(
    val answers: List<DecisionAnswer> = emptyList(),
    val usage: Usage? = null,
    val error: OpenAiError? = null,
)

@Serializable
data class QueryResult(
    val timestamp: String,
    val ad: String,
    val lang: String,
    val expected: String,
    val expectedMinor: String?,
    val kind: String,
    val stage: String,
    val approach: String = "",
    val model: String,
    val durationMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val predictions: List<String>,
    val probabilities: List<DecisionProbability> = emptyList(),
    val rawResponse: String?,
    val correct: Boolean?,
    val error: String?,
)

data class FlatUnit(val code: String, val label: String, val minorCode: String)

data class MinorInfo(val code: String, val label: String, val unitLabels: List<String>)

data class AdInfo(val code: String, val lang: String, val slug: String)

val jsonConfig = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** UTC timestamp safe for file names, e.g. 2026-11-02T19-21-11Z. */
fun fileTimestamp(now: Instant = Instant.now()): String =
    now.toString().substring(0, 19).replace(':', '-') + "Z"

fun flattenUnits(occupations: Occupations): List<FlatUnit> =
    occupations.majors.flatMap { m ->
        m.subMajors.flatMap { s ->
            s.minors.flatMap { minor ->
                minor.units.map { FlatUnit(it.code, it.label, minor.code) }
            }
        }
    }

fun minorsOf(occupations: Occupations): List<MinorInfo> =
    occupations.majors.flatMap { m ->
        m.subMajors.flatMap { s ->
            s.minors.map { minor ->
                MinorInfo(minor.code, minor.label, minor.units.map { it.label })
            }
        }
    }

fun parseAdFileName(name: String): AdInfo? {
    val m = Regex("^(\\d{4})-([a-z]{2})-(.+)\\.md$").matchEntire(name) ?: return null
    return AdInfo(m.groupValues[1], m.groupValues[2], m.groupValues[3])
}

fun parsePredictions(raw: String, key: String): List<String> {
    val obj = jsonConfig.parseToJsonElement(raw).jsonObject
    val arr = obj[key]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        (el as? JsonPrimitive)?.content
    }
}

fun main(args: Array<String>) = run(args)
