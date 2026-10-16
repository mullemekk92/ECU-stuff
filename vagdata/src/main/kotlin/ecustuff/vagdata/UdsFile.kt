package ecustuff.vagdata

import java.io.File

/**
 * A `.uds` file: UDS ECU descriptions made of `[NAME]` ... `[/NAME]` segments whose lines are
 * comma separated rows. Known segment names include CMP, MWB, ADP, XPL, DTC, INC, SLV, GES, SOT,
 * FFMUX and IDN; the shared tables use TXT, MUX, DOP, UNT and DIR.
 *
 * This class only splits the container. What the columns mean (value types, scaling ...) is a
 * separate layer.
 */
class UdsFile(val name: String, val segments: Map<String, List<List<String>>>) {
    /** Rows of every `[NAME]` block in the file, in order (a segment may appear more than once). */
    fun rows(segment: String): List<List<String>> = segments[segment.uppercase()].orEmpty()

    companion object {
        private val MARKER = Regex("\\[(/?)([A-Za-z0-9_]+)]")

        /** Lines outside any segment and blank lines are ignored; segment names are case-insensitive. */
        fun parse(text: String, name: String = ""): UdsFile {
            val segments = LinkedHashMap<String, MutableList<List<String>>>()
            var current: MutableList<List<String>>? = null
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val marker = MARKER.matchEntire(line)
                if (marker != null) {
                    current = if (marker.groupValues[1].isEmpty()) {
                        segments.getOrPut(marker.groupValues[2].uppercase()) { ArrayList() }
                    } else {
                        null
                    }
                    continue
                }
                current?.add(raw.split(','))
            }
            return UdsFile(name, segments)
        }

        fun read(file: File): UdsFile = parse(LabelFileReader.readText(file), file.name)
    }
}
