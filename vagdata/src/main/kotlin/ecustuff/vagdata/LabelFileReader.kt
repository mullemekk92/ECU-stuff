package ecustuff.vagdata

import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * Opens the app's `.ldat`, `.cdat` and `.uds` files.
 *
 * Each one is a zip archive holding a single entry with the same name as the file. The entry is
 * encrypted with ZipCrypto; the password is the upper-case hex MD5 of the upper-cased file name
 * without its extension (so `00-01.ldat` is opened with `MD5("00-01")`).
 */
object LabelFileReader {
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")

    /** The last path component of [path], accepting both `/` and `\` separators. */
    fun fileNameOf(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    fun passwordFor(fileName: String): ByteArray {
        val name = fileNameOf(fileName)
        val baseName = if ('.' in name) name.substringBeforeLast('.') else name
        val digest = MessageDigest.getInstance("MD5").digest(baseName.uppercase().toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return hex.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Returns the decrypted, still undecoded, bytes of the file's single entry. */
    fun decrypt(fileBytes: ByteArray, fileName: String): ByteArray =
        EncryptedZip.readEntry(fileBytes, fileNameOf(fileName), passwordFor(fileName))

    /**
     * Russian files (`*-rus.*`, or anything inside a directory called `rus`) are Windows-1251,
     * everything else is Windows-1252. Bytes that are undefined in those code pages decode to U+FFFD.
     */
    fun charsetFor(fileName: String, parentDirName: String? = null): Charset {
        val name = fileNameOf(fileName)
        val baseName = if ('.' in name) name.substringBeforeLast('.') else name
        val russian = baseName.endsWith("-rus", ignoreCase = true) || parentDirName.equals("rus", ignoreCase = true)
        return if (russian) WINDOWS_1251 else WINDOWS_1252
    }

    fun readText(fileBytes: ByteArray, fileName: String, parentDirName: String? = null): String =
        String(decrypt(fileBytes, fileName), charsetFor(fileName, parentDirName))

    fun readText(file: File): String = readText(file.readBytes(), file.name, file.absoluteFile.parentFile?.name)

    private const val HEX = "0123456789ABCDEF"
}
