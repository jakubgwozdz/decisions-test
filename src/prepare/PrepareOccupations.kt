package prepare

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.getValue
import org.jetbrains.kotlinx.dataframe.io.ColType
import org.jetbrains.kotlinx.dataframe.io.readCsv
import java.io.File

@Serializable
data class Occupations(val majors: List<MajorGroup>)

@Serializable
data class MajorGroup(val code: String, val label: String, val subMajors: List<SubMajorGroup>)

@Serializable
data class SubMajorGroup(val code: String, val label: String, val minors: List<MinorGroup>)

@Serializable
data class MinorGroup(val code: String, val label: String, val units: List<UnitGroup>)

@Serializable
data class UnitGroup(val code: String, val label: String)

private data class Row(
    val major: String,
    val majorLabel: String,
    val subMajor: String,
    val subMajorLabel: String,
    val minor: String,
    val minorLabel: String,
    val unit: String,
    val description: String,
)

private const val ISCO_URL =
    "https://www.ilo.org/ilostat-files/ISCO/newdocs-08-2021/ISCO-08/ISCO-08%20EN.csv"

private val localDir = File("local")
private val csvFile = File(localDir, "isco-08-en.csv")
private val jsonFile = File(localDir, "occupations.json")

fun run(args: Array<String>) = runBlocking {
    localDir.mkdirs()
    downloadCsv(ISCO_URL, csvFile)

    val df = DataFrame.readCsv(
        file = csvFile,
        colTypes = mapOf(
            "ISCO_version" to ColType.String,
            "major" to ColType.String,
            "major_label" to ColType.String,
            "sub_major" to ColType.String,
            "sub_major_label" to ColType.String,
            "minor" to ColType.String,
            "minor_label" to ColType.String,
            "unit" to ColType.String,
            "description" to ColType.String,
        ),
    )
    val occupations = buildOccupations(df)

    val json = Json { prettyPrint = true }
    jsonFile.writeText(json.encodeToString(occupations))

    val unitCount = occupations.majors.sumOf { m ->
        m.subMajors.sumOf { s -> s.minors.sumOf { it.units.size } }
    }
    println("Saved ${jsonFile.path}: ${occupations.majors.size} major groups, $unitCount unit groups")
}

private suspend fun downloadCsv(url: String, target: File) {
    if (target.exists()) {
        println("CSV already exists: ${target.path}, skipping download")
        return
    }
    println("Downloading $url ...")
    HttpClient(CIO).use { client ->
        target.writeBytes(client.get(url).bodyAsBytes())
    }
    println("Saved ${target.path}")
}

fun buildOccupations(df: DataFrame<*>): Occupations {
    val rows = mutableListOf<Row>()
    for (row in df) {
        if (row.getValue<Any>("ISCO_version") != "ISCO-08") continue
        rows.add(
            Row(
                major = row.getValue("major"),
                majorLabel = row.getValue("major_label"),
                subMajor = row.getValue("sub_major"),
                subMajorLabel = row.getValue("sub_major_label"),
                minor = row.getValue("minor"),
                minorLabel = row.getValue("minor_label"),
                unit = row.getValue("unit"),
                description = row.getValue("description"),
            ),
        )
    }

    val byMajor = rows.groupByTo(LinkedHashMap()) { it.major }
    return Occupations(
        byMajor.map { (majorCode, majorRows) ->
            val bySubMajor = majorRows.groupByTo(LinkedHashMap()) { it.subMajor }
            MajorGroup(
                code = majorCode,
                label = majorRows.first().majorLabel,
                subMajors = bySubMajor.map { (subMajorCode, subMajorRows) ->
                    val byMinor = subMajorRows.groupByTo(LinkedHashMap()) { it.minor }
                    SubMajorGroup(
                        code = subMajorCode,
                        label = subMajorRows.first().subMajorLabel,
                        minors = byMinor.map { (minorCode, minorRows) ->
                            val byUnit = minorRows.groupByTo(LinkedHashMap()) { it.unit }
                            MinorGroup(
                                code = minorCode,
                                label = minorRows.first().minorLabel,
                                units = byUnit.map { (unitCode, unitRows) ->
                                    val descriptions = unitRows.map { it.description }.toSet()
                                    require(descriptions.size == 1) {
                                        "Unit $unitCode has ${descriptions.size} different descriptions: $descriptions"
                                    }
                                    UnitGroup(code = unitCode, label = descriptions.first())
                                },
                            )
                        },
                    )
                },
            )
        },
    )
}

fun main(args: Array<String>) = run(args)
