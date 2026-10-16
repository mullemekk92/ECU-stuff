package ecustuff.vagdata

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LabelFileReaderTest {
    private fun password(fileName: String) = String(LabelFileReader.passwordFor(fileName), Charsets.US_ASCII)

    @Test
    fun `password is the upper-case hex MD5 of the upper-cased base name`() {
        // Expected values computed independently with Python's hashlib.
        assertEquals("C140326DDA90FCA55F3803FD2FB03699", password("00-01.ldat"))
        assertEquals("EAB5072B029DC1E09D4A5CFB88ECB95F", password("1k-37.ldat"))
        assertEquals("EDE369B7D610A023DF0204EF8EFDC6D8", password("zz0-123-45x-37.ldat"))
    }

    @Test
    fun `password ignores directories, extension and case`() {
        val expected = password("00-01.ldat")
        assertEquals(expected, password("/storage/emulated/0/Vag/en/labels/00-01.ldat"))
        assertEquals(expected, password("C:\\Vag\\en\\labels\\00-01.LDAT"))
        assertEquals(expected, password("00-01"))
    }

    @Test
    fun `only the last extension is dropped`() {
        // MD5("A.B"), not MD5("A"): everything before the last dot is kept.
        assertEquals("D9A3A06288E3AF307B8F6C61CC4972A8", password("a.b.cdat"))
    }

    @Test
    fun `reads an encrypted label file`() {
        val file = resourceFile("data/en/labels/abc-def-ghi.ldat")
        assertEquals("001,0,Direct file without suffix\r\n", LabelFileReader.readText(file))
    }

    @Test
    fun `a file opened under the wrong name is rejected`() {
        val bytes = resource("data/en/labels/abc-def-ghi.ldat")
        assertFailsWith<ZipFormatException> { LabelFileReader.readText(bytes, "abc-def-ghj-k.ldat") }
    }

    @Test
    fun `Windows-1252 text decodes and undefined bytes do not throw`() {
        val text = LabelFileReader.readText(resourceFile("data/en/labels/zz9-000-001.ldat"))
        assertTrue("Größe" in text, text)
        assertTrue('\uFFFD' in text, "the undefined byte 0x81 should decode to U+FFFD")
    }

    @Test
    fun `Russian files are decoded as Windows-1251`() {
        val text = LabelFileReader.readText(resourceFile("data/en/labels/zz9-000-002-rus.ldat"))
        assertTrue("Привет" in text, text)
    }

    @Test
    fun `charset selection follows the file or directory name`() {
        val w1251 = Charset.forName("windows-1251")
        val w1252 = Charset.forName("windows-1252")
        assertEquals(w1251, LabelFileReader.charsetFor("0a-01-rus.ldat"))
        assertEquals(w1251, LabelFileReader.charsetFor("0A-01-RUS.LDAT"))
        assertEquals(w1251, LabelFileReader.charsetFor("0a-01.ldat", parentDirName = "rus"))
        assertEquals(w1252, LabelFileReader.charsetFor("0a-01.ldat", parentDirName = "labels"))
        assertEquals(w1252, LabelFileReader.charsetFor("0a-01.ldat"))
    }
}
