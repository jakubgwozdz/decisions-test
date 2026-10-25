package prepare

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.io.ColType
import org.jetbrains.kotlinx.dataframe.io.readCsvStr

class PrepareOccupationsTest {

    private val sampleCsv = """
        ISCO_version,major,major_label,sub_major,sub_major_label,minor,minor_label,unit,description
        ISCO-08,1,Managers,11,"Chief Executives, Senior Officials and Legislators",111,Legislators and Senior Officials,1111,Legislators
        ISCO-08,1,Managers,11,"Chief Executives, Senior Officials and Legislators",111,Legislators and Senior Officials,1112,Senior Government Officials
        ISCO-08,1,Managers,12,Administrative and Commercial Managers,121,Business Services and Administration Managers,1211,Finance Managers
        ISCO-68,X,Workers Not Classifiable by Occupation ,,,X1,New Workers Seeking Employment ,X10,New workers seeking employment
    """.trimIndent()

    private fun readSample() = DataFrame.readCsvStr(
        sampleCsv,
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

    @Test
    fun groupsHierarchyAndFiltersToIsco08() {
        val result = buildOccupations(readSample())

        assertEquals(1, result.majors.size)

        val major = result.majors[0]
        assertEquals("1", major.code)
        assertEquals("Managers", major.label)
        assertEquals(2, major.subMajors.size)

        val subMajor = major.subMajors[0]
        assertEquals("11", subMajor.code)
        assertEquals("Chief Executives, Senior Officials and Legislators", subMajor.label)
        assertEquals(1, subMajor.minors.size)

        val minor = subMajor.minors[0]
        assertEquals("111", minor.code)
        assertEquals(2, minor.units.size)
        assertEquals(UnitGroup("1111", "Legislators"), minor.units[0])
        assertEquals(UnitGroup("1112", "Senior Government Officials"), minor.units[1])

        val minor2 = major.subMajors[1].minors[0]
        assertEquals(1, minor2.units.size)
        assertEquals(UnitGroup("1211", "Finance Managers"), minor2.units[0])
    }
}
