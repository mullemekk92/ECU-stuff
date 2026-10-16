package ecustuff.vagdata

/**
 * The traditional PKWARE stream cipher ("ZipCrypto") used by the label files.
 *
 * It is cryptographically weak and is only an obfuscation layer here; it exists so
 * the files can be read without a third-party zip library.
 */
internal class ZipCrypto(password: ByteArray) {
    private var key0 = 0x12345678
    private var key1 = 0x23456789
    private var key2 = 0x34567890

    init {
        for (b in password) updateKeys(b.toInt() and 0xFF)
    }

    /** Decrypts one ciphertext byte (0..255) and advances the cipher state. */
    fun decrypt(cipherByte: Int): Int {
        val plain = (cipherByte xor keystreamByte()) and 0xFF
        updateKeys(plain)
        return plain
    }

    private fun keystreamByte(): Int {
        val t = (key2 or 2) and 0xFFFF
        return ((t * (t xor 1)) ushr 8) and 0xFF
    }

    private fun updateKeys(plainByte: Int) {
        key0 = crcStep(key0, plainByte)
        key1 = (key1 + (key0 and 0xFF)) * 134775813 + 1
        key2 = crcStep(key2, key1 ushr 24)
    }

    private companion object {
        val CRC_TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        fun crcStep(crc: Int, b: Int): Int = (crc ushr 8) xor CRC_TABLE[(crc xor b) and 0xFF]
    }
}
