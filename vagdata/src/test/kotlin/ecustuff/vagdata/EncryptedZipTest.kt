package ecustuff.vagdata

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The archives are produced by Info-ZIP (`scripts/make-test-fixtures.sh`), not by our own code. */
class EncryptedZipTest {
    private val hello = resource("zip/hello.txt")
    private val secret = "secret".toByteArray()

    @Test
    fun `reads a deflated encrypted entry`() {
        assertContentEquals(hello, EncryptedZip.readEntry(resource("zip/file-input.zip"), "hello.txt", secret))
    }

    @Test
    fun `reads an entry archived from stdin`() {
        assertContentEquals(hello, EncryptedZip.readEntry(resource("zip/stdin-input.zip"), "-", secret))
    }

    @Test
    fun `reads a stored encrypted entry`() {
        val zip = resource("zip/stored.zip")
        assertEquals(0, EncryptedZip.entries(zip).single().let { it.size - hello.size })
        assertContentEquals(hello, EncryptedZip.readEntry(zip, "hello.txt", secret))
    }

    @Test
    fun `reads an unencrypted entry and ignores the password`() {
        val zip = resource("zip/plain.zip")
        assertFalse(EncryptedZip.entries(zip).single().isEncrypted)
        assertContentEquals(hello, EncryptedZip.readEntry(zip, "hello.txt", ByteArray(0)))
    }

    @Test
    fun `entry names match case-insensitively`() {
        assertContentEquals(hello, EncryptedZip.readEntry(resource("zip/file-input.zip"), "HELLO.TXT", secret))
    }

    @Test
    fun `lists entries with their encryption flag`() {
        val entry = EncryptedZip.entries(resource("zip/file-input.zip")).single()
        assertEquals("hello.txt", entry.name)
        assertEquals(hello.size, entry.size)
        assertTrue(entry.isEncrypted)
    }

    @Test
    fun `wrong passwords are always reported as such`() {
        val zip = resource("zip/file-input.zip")
        // 300 candidates also exercises the ~1-in-128 chance that the 1-byte check passes by luck:
        // the CRC / inflate failure must still end up as WrongPasswordException.
        for (i in 0 until 300) {
            assertFailsWith<WrongPasswordException>("candidate $i") {
                EncryptedZip.readEntry(zip, "hello.txt", "wrong-$i".toByteArray())
            }
        }
    }

    @Test
    fun `a missing entry is a format error not a password error`() {
        val e = assertFailsWith<ZipFormatException> { EncryptedZip.readEntry(resource("zip/file-input.zip"), "nope.txt", secret) }
        assertFalse(e is WrongPasswordException)
    }

    @Test
    fun `truncated and garbage input is rejected`() {
        val zip = resource("zip/file-input.zip")
        assertFailsWith<ZipFormatException> { EncryptedZip.entries(zip.copyOf(zip.size / 2)) }
        assertFailsWith<ZipFormatException> { EncryptedZip.entries(ByteArray(10)) }
        assertFailsWith<ZipFormatException> { EncryptedZip.entries(ByteArray(4096) { it.toByte() }) }
    }

    @Test
    fun `a corrupted payload is detected by the CRC`() {
        val zip = resource("zip/plain.zip").copyOf()
        // Info-ZIP writes extra fields into the local header, so aim well inside the ~300 deflated bytes.
        val middle = zip.size / 3
        zip[middle] = (zip[middle].toInt() xor 0x55).toByte()
        assertFailsWith<ZipFormatException> { EncryptedZip.readEntry(zip, "hello.txt", secret) }
    }
}
