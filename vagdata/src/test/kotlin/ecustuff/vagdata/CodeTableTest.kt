package ecustuff.vagdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeTableTest {
    @Test
    fun `reads the encrypted fixture`() {
        val table = CodeTable.read(resourceFile("data/en/codes.cdat"))
        assertEquals(4, table.size)
        assertTrue(table.rejectedLines.isEmpty())
        assertEquals("Test Brake Unit", table[1])
        assertEquals("Largest key", table[4294967295L])
        assertNull(table[3])
    }

    @Test
    fun `the text keeps everything after the first comma`() {
        val table = CodeTable.parse("00000002,Test Gearbox, with a comma in the text\r\n")
        assertEquals("Test Gearbox, with a comma in the text", table[2])
    }

    @Test
    fun `bad lines are rejected with 1-based numbers and blank lines are ignored`() {
        val table = CodeTable.parse(
            listOf(
                "1,ok",
                "",
                "no comma here",
                "abc,non numeric key",
                "-5,negative key",
                "4294967296,key above 32 bits",
                "1,duplicate key",
                "2,also ok",
            ).joinToString("\n"),
        )
        assertEquals(setOf(1L, 2L), table.entries.keys)
        assertEquals("ok", table[1])
        assertEquals(listOf(3, 4, 5, 6, 7), table.rejectedLines)
    }
}
