package ecustuff.vagdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UdsFileTest {
    @Test
    fun `splits an encrypted file into segments`() {
        val uds = UdsFile.read(resourceFile("data/en/uds_ev/sample.uds"))
        assertEquals("sample.uds", uds.name)
        assertEquals(listOf("CMP", "DTC", "MWB"), uds.segments.keys.toList())
        assertEquals(listOf(listOf("123456", "123456", "1163", "SAMPLE_ECU", "10")), uds.rows("CMP"))
        assertEquals(listOf(listOf("000010", "0F")), uds.rows("MWB"))
    }

    @Test
    fun `a segment that appears twice is merged in order`() {
        val uds = UdsFile.read(resourceFile("data/en/uds_ev/sample.uds"))
        assertEquals(listOf("000001", "000002", "000003"), uds.rows("DTC").map { it[0] })
    }

    @Test
    fun `lookup is case-insensitive and unknown segments are empty`() {
        val uds = UdsFile.parse("[dtc]\r\n1,A\r\n[/dtc]\r\n")
        assertEquals(1, uds.rows("DTC").size)
        assertEquals(1, uds.rows("dtc").size)
        assertTrue(uds.rows("MWB").isEmpty())
    }

    @Test
    fun `stray lines outside segments are ignored and empty columns survive`() {
        val uds = UdsFile.parse("junk\r\n[IDN]\r\na,,b,\r\n\r\n[/IDN]\r\nmore junk\r\n")
        assertEquals(listOf(listOf("a", "", "b", "")), uds.rows("IDN"))
    }

    @Test
    fun `an unterminated segment runs to the end of the file`() {
        assertEquals(2, UdsFile.parse("[TXT]\n1,a\n2,b\n").rows("TXT").size)
    }

    @Test
    fun `a new start marker closes the previous segment`() {
        val uds = UdsFile.parse("[A]\n1\n[B]\n2\n[/B]\n3\n")
        assertEquals(listOf(listOf("1")), uds.rows("A"))
        assertEquals(listOf(listOf("2")), uds.rows("B"))
    }
}
