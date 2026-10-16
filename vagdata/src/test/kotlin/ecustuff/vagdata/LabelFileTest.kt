package ecustuff.vagdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LabelFileTest {
    private fun parse(vararg lines: String) = LabelFile.parse(lines.joinToString("\r\n"))

    private inline fun <reified T : LabelRecord> LabelFile.single(): T = records.filterIsInstance<T>().single()

    @Test
    fun `comments blanks and line numbers`() {
        val file = LabelFile.parse("; header\r\n\r\n   \r\n001,0,Name   ; trailing comment\r\n")
        val record = file.records.single()
        assertEquals(GroupLabel(4, GroupKind.MEASURING, 1, 0, listOf("Name")), record)
    }

    @Test
    fun `semicolons end the line even inside label text`() {
        // Same behaviour as the Deep OBD app, which cuts at the first ';'.
        val record = parse("001,1,Before; after").single<GroupLabel>()
        assertEquals(listOf("Before"), record.texts)
    }

    @Test
    fun `measuring adaptation and basic setting groups`() {
        val file = parse(
            "002,3,Load,%,Range: 15...25\\nSpecification: ok",
            "A004,5,Headlight Washer Activation Time",
            "a004,7,2 = English",
            "B043,1,Engine Speed,(G28),Range: 0...7000 RPM",
            "10,1,Legacy one and two digit groups",
        )
        assertEquals(
            GroupLabel(1, GroupKind.MEASURING, 2, 3, listOf("Load", "%", "Range: 15...25\\nSpecification: ok")),
            file.records[0],
        )
        assertEquals(GroupLabel(2, GroupKind.ADAPTATION, 4, 5, listOf("Headlight Washer Activation Time")), file.records[1])
        assertEquals(GroupKind.ADAPTATION, assertIs<GroupLabel>(file.records[2]).kind)
        assertEquals(listOf("Engine Speed", "(G28)", "Range: 0...7000 RPM"), file.basicSetting(43).single().texts)
        assertEquals(10, assertIs<GroupLabel>(file.records[4]).group)
        assertEquals(1, file.measuringBlock(2).size)
        assertEquals(2, file.adaptationChannel(4).size)
    }

    @Test
    fun `login settings and coding sections`() {
        val file = parse("L01,Login help", "S00,Security access text", "C00,Coding title", "c1,00000 - Manual")
        assertEquals(
            listOf(
                SectionLabel(1, SectionKind.LOGIN, 1, listOf("Login help")),
                SectionLabel(2, SectionKind.SETTINGS, 0, listOf("Security access text")),
                SectionLabel(3, SectionKind.CODING, 0, listOf("Coding title")),
                SectionLabel(4, SectionKind.CODING, 1, listOf("00000 - Manual")),
            ),
            file.records,
        )
    }

    @Test
    fun `redirects`() {
        val file = parse(
            "REDIRECT,5QX-959-X93-42.CLB,5Q4-959-393-C",
            "redirect,A.LBL,AAA-BBB-CCC-?,hw  ; comment",
            "REDIRECT,B.LBL,-B,-C",
            "REDIRECT,only-two-fields",
        )
        assertEquals(3, file.redirects.size)
        val first = file.redirects[0]
        assertEquals("5QX-959-X93-42.CLB", first.target)
        assertEquals("5Q4-959-393-C", first.pattern)
        assertEquals(null, first.source)
        assertEquals("HW", file.redirects[1].source)
        assertEquals(listOf("-B", "-C"), file.redirects[2].arguments)
        assertEquals(null, file.redirects[2].source)
        assertIs<OtherRecord>(file.records[3])
    }

    @Test
    fun `long coding in all its shapes`() {
        val file = parse(
            "LC,02,2,Cold Lamp Diagnosis active",
            "LC,01,0~7,05,Dynaudio Sound System",
            "LC,11,0-7,1,Front Parking Light Dimming",
            "LC,03,2=,Description line, with comma",
            "LC,05,1~3,Range without a value",
            "LC,04,6,Text, with a comma",
        )
        val lc = file.longCoding()
        assertEquals(LongCodingLabel(1, 2, 2, 2, null, null, "Cold Lamp Diagnosis active"), lc[0])
        assertEquals(LongCodingLabel(2, 1, 0, 7, 5, null, "Dynaudio Sound System"), lc[1])
        assertEquals(LongCodingLabel(3, 11, 0, 7, 1, null, "Front Parking Light Dimming"), lc[2])
        assertEquals(LongCodingLabel(4, 3, null, null, null, 2, "Description line, with comma"), lc[3])
        assertEquals(LongCodingLabel(5, 5, 1, 3, null, null, "Range without a value"), lc[4])
        assertEquals("Text, with a comma", lc[5].text)
    }

    @Test
    fun `values of bit ranges are hexadecimal`() {
        assertEquals(0x0A, parse("LC,01,0~3,0A,Ten").single<LongCodingLabel>().value)
    }

    @Test
    fun `malformed long coding and unknown tags are kept as other records`() {
        val file = parse(
            "LC,xx,1,bad byte",
            "LC,01,zz,bad bit spec",
            "LC,01",
            "O,021-906-018-AFP",
            "SN,MSA12",
            "W,VCR010004",
            "8X,AU21",
            "#\"Seat\",\"Ibiza\",\"AKK\",\"2000\"",
            "RJ,4,4,44,365",
            ": Component: free text",
            "A004",
            "123",
        )
        assertEquals(file.records.size, file.records.filterIsInstance<OtherRecord>().size)
        assertEquals("O", (file.records[3] as OtherRecord).tag)
        assertEquals(listOf("8X", "AU21"), (file.records[6] as OtherRecord).fields)
    }

    @Test
    fun `reads an encrypted file`() {
        val file = LabelFile.read(resourceFile("data/en/labels/zz-37.ldat"))
        assertEquals("zz-37.ldat", file.name)
        assertEquals(4, file.redirects.size)
        assertTrue(file.records.all { it is Redirect })
    }
}
