package ecustuff.vagdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LabelResolverTest {
    private val resolver = LabelResolver(resourceFile("data/en/labels"))

    private fun resolve(
        partNumber: String,
        address: Int = 0x01,
        hardware: String? = null,
        subsystem: String? = null,
        subsystemIndex: Int? = null,
    ) = resolver.resolve(LabelQuery(partNumber, address, hardware, subsystem, subsystemIndex))?.name

    @Test
    fun `finds a file named after the full part number`() {
        assertEquals("abc-def-ghj-k.ldat", resolve("ABCDEFGHJK"))
    }

    @Test
    fun `falls back to the file named after the base part number`() {
        assertEquals("abc-def-ghi.ldat", resolve("ABCDEFGHI"))
        assertEquals("abc-def-ghi.ldat", resolve("ABCDEFGHIX"))
    }

    @Test
    fun `follows a redirect file with a wildcard tail`() {
        assertEquals("zz0-123-45x-37.ldat", resolve("ZZ0123456", address = 0x37))
        assertEquals("zz0-123-45x-37.ldat", resolve("ZZ0123456C", address = 0x37))
        assertNull(resolve("ZZ0123457", address = 0x37))
    }

    @Test
    fun `exact redirect patterns must match completely`() {
        assertEquals("yy0-999-888.ldat", resolve("ZZ0777888A", address = 0x37))
        assertNull(resolve("ZZ0777888B", address = 0x37))
        assertNull(resolve("ZZ0777888AB", address = 0x37))
    }

    @Test
    fun `a redirect is only consulted for its own address`() {
        assertNull(resolve("ZZ0123456", address = 0x38))
    }

    @Test
    fun `hardware rules match against the hardware part number`() {
        assertEquals("hw0-000-001.ldat", resolve("ZZ0999999", address = 0x37, hardware = "AAABBBCCCX"))
        assertEquals("hw0-000-001.ldat", resolve("ZZ0999999", address = 0x37, hardware = "AAABBBCCC"))
        assertNull(resolve("ZZ0999999", address = 0x37))
    }

    @Test
    fun `subsystem rules match against the subsystem part number`() {
        assertEquals("sl0-000-002.ldat", resolve("ZZ0999999", address = 0x37, subsystem = "DDDEEEFFF"))
        assertNull(resolve("ZZ0999999", address = 0x37, subsystem = "DDDEEEFFG"))
    }

    @Test
    fun `a subsystem index starts from the per-index redirect file`() {
        // dd-37-02.ldat: first two characters of the subsystem part number, address, index + 1 in hex.
        assertEquals("ss0-111-222.ldat", resolve("ZZ0999999", address = 0x37, subsystem = "DDDEEEFFF", subsystemIndex = 1))
        assertNull(resolve("ZZ0999999", address = 0x37, subsystem = "DDDEEEFFF", subsystemIndex = 2))
    }

    @Test
    fun `a direct file can redirect through part number suffixes`() {
        assertEquals("rr0-300-400.ldat", resolve("QQ0100200C"))
        assertEquals("rr0-300-400.ldat", resolve("QQ0100200B"))
        assertEquals("qq0-100-200.ldat", resolve("QQ0100200"))
        assertEquals("qq0-100-200.ldat", resolve("QQ0100200Z"))
    }

    @Test
    fun `unknown and too-short part numbers give no result`() {
        assertNull(resolve("NOPE00000"))
        assertNull(resolve("ABC"))
        assertNull(resolve(""))
    }

    @Test
    fun `wildcard patterns`() {
        val tail = LabelResolver.wildcardToRegex("1K0-035-274-???")
        assertTrue(tail.containsMatchIn("1K0-035-274"))
        assertTrue(tail.containsMatchIn("1k0-035-274-b"))
        assertFalse(tail.containsMatchIn("1K0-035-275"))

        val middle = LabelResolver.wildcardToRegex("A?C")
        assertTrue(middle.containsMatchIn("ABC"))
        assertFalse(middle.containsMatchIn("ABBC"))
        assertFalse(middle.containsMatchIn("AC"))

        val exact = LabelResolver.wildcardToRegex("AB.C")
        assertTrue(exact.containsMatchIn("AB.C"))
        assertFalse(exact.containsMatchIn("ABXC"))
    }
}
