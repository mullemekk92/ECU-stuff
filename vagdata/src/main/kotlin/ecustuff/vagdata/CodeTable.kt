package ecustuff.vagdata

import java.io.File

/**
 * Lookup table from a numeric code to its text, read from `codes.cdat`.
 *
 * Every line is `<unsigned 32-bit number>,<text>`; the text may itself contain commas.
 */
class CodeTable(
    val entries: Map<Long, String>,
    /** 1-based numbers of lines that were ignored: no comma, a non-numeric key, or a repeated key. */
    val rejectedLines: List<Int>,
) {
    val size: Int get() = entries.size

    operator fun get(code: Long): String? = entries[code]

    companion object {
        fun parse(text: String): CodeTable {
            val entries = LinkedHashMap<Long, String>()
            val rejected = ArrayList<Int>()
            text.lines().forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val comma = line.indexOf(',')
                val key = if (comma < 0) null else line.substring(0, comma).trim().toLongOrNull()
                if (key == null || key !in 0..0xFFFFFFFFL || key in entries) {
                    rejected += index + 1
                } else {
                    entries[key] = line.substring(comma + 1)
                }
            }
            return CodeTable(entries, rejected)
        }

        fun read(file: File): CodeTable = parse(LabelFileReader.readText(file))
    }
}
