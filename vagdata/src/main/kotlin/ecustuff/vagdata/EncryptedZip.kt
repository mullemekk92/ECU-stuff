package ecustuff.vagdata

import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** The bytes are not a zip archive we can read (truncated, corrupt, unsupported feature). */
open class ZipFormatException(message: String) : IOException(message)

/** The entry is encrypted and the supplied password does not decrypt it. */
class WrongPasswordException(message: String) : ZipFormatException(message)

/**
 * Minimal zip reader: classic (non-Zip64) archives, stored or deflated entries,
 * optionally protected with ZipCrypto. Entries are located through the central directory, so
 * archives written with data descriptors (flag bit 3, as the label files are) work.
 */
object EncryptedZip {
    /** Upper bound for one entry, as a guard against corrupt or hostile size fields. */
    const val MAX_ENTRY_BYTES = 256 * 1024 * 1024

    private const val SIG_LOCAL = 0x04034b50L
    private const val SIG_CENTRAL = 0x02014b50L
    private const val SIG_END = 0x06054b50L
    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_UTF8_NAME = 0x0800
    private const val METHOD_STORED = 0
    private const val METHOD_DEFLATED = 8
    private const val ENCRYPTION_HEADER_BYTES = 12
    private const val END_RECORD_BYTES = 22
    private const val CENTRAL_HEADER_BYTES = 46
    private const val LOCAL_HEADER_BYTES = 30
    private const val ZIP64_MARKER = 0xFFFFFFFFL

    class Entry internal constructor(
        val name: String,
        internal val flags: Int,
        internal val method: Int,
        internal val dosTime: Int,
        internal val crc32: Long,
        internal val compressedSize: Int,
        val size: Int,
        internal val localHeaderOffset: Int,
    ) {
        val isEncrypted: Boolean get() = flags and FLAG_ENCRYPTED != 0
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    /** Lists the entries from the central directory. */
    fun entries(zip: ByteArray): List<Entry> {
        if (zip.size < END_RECORD_BYTES) throw ZipFormatException("too small to be a zip archive")
        val searchLimit = maxOf(0, zip.size - END_RECORD_BYTES - 0xFFFF)
        var end = zip.size - END_RECORD_BYTES
        while (end >= searchLimit && zip.u32(end) != SIG_END) end--
        if (end < searchLimit) throw ZipFormatException("end of central directory not found")

        val count = zip.u16(end + 10)
        val directoryOffset = zip.u32(end + 16)
        if (count == 0xFFFF || directoryOffset == ZIP64_MARKER) throw ZipFormatException("Zip64 archives are not supported")
        if (directoryOffset >= zip.size) throw ZipFormatException("central directory offset out of range")

        val result = ArrayList<Entry>(count)
        var p = directoryOffset.toInt()
        repeat(count) {
            if (zip.u32(p) != SIG_CENTRAL) throw ZipFormatException("bad central directory entry")
            val flags = zip.u16(p + 8)
            val compressedSize = zip.u32(p + 20)
            val size = zip.u32(p + 24)
            val nameLength = zip.u16(p + 28)
            val extraLength = zip.u16(p + 30)
            val commentLength = zip.u16(p + 32)
            val localOffset = zip.u32(p + 42)
            if (compressedSize == ZIP64_MARKER || size == ZIP64_MARKER || localOffset == ZIP64_MARKER) {
                throw ZipFormatException("Zip64 entries are not supported")
            }
            if (compressedSize > MAX_ENTRY_BYTES || size > MAX_ENTRY_BYTES) throw ZipFormatException("entry too large")
            if (localOffset >= zip.size) throw ZipFormatException("local header offset out of range")
            zip.requireRange(p + CENTRAL_HEADER_BYTES, nameLength)
            val charset = if (flags and FLAG_UTF8_NAME != 0) Charsets.UTF_8 else Charsets.ISO_8859_1
            result += Entry(
                name = String(zip, p + CENTRAL_HEADER_BYTES, nameLength, charset),
                flags = flags,
                method = zip.u16(p + 10),
                dosTime = zip.u16(p + 12),
                crc32 = zip.u32(p + 16),
                compressedSize = compressedSize.toInt(),
                size = size.toInt(),
                localHeaderOffset = localOffset.toInt(),
            )
            p += CENTRAL_HEADER_BYTES + nameLength + extraLength + commentLength
        }
        return result
    }

    /**
     * Reads and, if necessary, decrypts the file entry called [entryName] (case-insensitive).
     *
     * @throws WrongPasswordException if the entry is encrypted and [password] is wrong
     * @throws ZipFormatException for any other problem with the archive
     */
    fun readEntry(zip: ByteArray, entryName: String, password: ByteArray): ByteArray {
        val entry = entries(zip).firstOrNull { !it.isDirectory && it.name.equals(entryName, ignoreCase = true) }
            ?: throw ZipFormatException("no entry named '$entryName'")
        return extract(zip, entry, password)
    }

    private fun extract(zip: ByteArray, entry: Entry, password: ByteArray): ByteArray {
        val local = entry.localHeaderOffset
        if (zip.u32(local) != SIG_LOCAL) throw ZipFormatException("bad local header for '${entry.name}'")
        val dataStart = local + LOCAL_HEADER_BYTES + zip.u16(local + 26) + zip.u16(local + 28)
        zip.requireRange(dataStart, entry.compressedSize)

        val payload: ByteArray = if (entry.isEncrypted) {
            if (entry.compressedSize < ENCRYPTION_HEADER_BYTES) throw ZipFormatException("encrypted entry too short")
            val cipher = ZipCrypto(password)
            var check = 0
            for (i in 0 until ENCRYPTION_HEADER_BYTES) check = cipher.decrypt(zip[dataStart + i].toInt() and 0xFF)
            // The spec says the check byte is the CRC's high byte, or the DOS time's high byte when a
            // data descriptor is used; writers differ, so accept either.
            val fromCrc = (entry.crc32 ushr 24).toInt() and 0xFF
            val fromTime = (entry.dosTime ushr 8) and 0xFF
            if (check != fromCrc && check != fromTime) throw WrongPasswordException("wrong password for '${entry.name}'")
            ByteArray(entry.compressedSize - ENCRYPTION_HEADER_BYTES) {
                cipher.decrypt(zip[dataStart + ENCRYPTION_HEADER_BYTES + it].toInt() and 0xFF).toByte()
            }
        } else {
            zip.copyOfRange(dataStart, dataStart + entry.compressedSize)
        }

        val data = try {
            when (entry.method) {
                METHOD_STORED -> payload
                METHOD_DEFLATED -> inflate(payload, entry.size)
                else -> throw ZipFormatException("unsupported compression method ${entry.method}")
            }
        } catch (e: DataFormatException) {
            throw corruption(entry, e.message)
        }
        if (data.size != entry.size) throw corruption(entry, "size mismatch")
        val crc = CRC32().apply { update(data) }.value
        if (crc != entry.crc32) throw corruption(entry, "CRC mismatch")
        return data
    }

    /** A wrong password can slip past the 1-byte check, so decryption failures are reported as such. */
    private fun corruption(entry: Entry, detail: String?): ZipFormatException =
        if (entry.isEncrypted) WrongPasswordException("wrong password or corrupt data in '${entry.name}' ($detail)")
        else ZipFormatException("corrupt data in '${entry.name}' ($detail)")

    private fun inflate(compressed: ByteArray, expectedSize: Int): ByteArray {
        if (expectedSize == 0) return ByteArray(0)
        val inflater = Inflater(true)
        try {
            // Raw deflate streams need one extra dummy input byte (see Inflater's documentation).
            inflater.setInput(compressed.copyOf(compressed.size + 1))
            val out = ByteArray(expectedSize)
            var filled = 0
            while (filled < expectedSize) {
                val n = inflater.inflate(out, filled, expectedSize - filled)
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                filled += n
            }
            if (filled != expectedSize) throw DataFormatException("inflated $filled of $expectedSize bytes")
            return out
        } finally {
            inflater.end()
        }
    }

    private fun ByteArray.requireRange(offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset.toLong() + length > size) throw ZipFormatException("archive is truncated")
    }

    private fun ByteArray.u16(i: Int): Int {
        requireRange(i, 2)
        return (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.u32(i: Int): Long {
        requireRange(i, 4)
        return (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
    }
}
