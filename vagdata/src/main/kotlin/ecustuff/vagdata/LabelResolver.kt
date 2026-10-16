package ecustuff.vagdata

import java.io.File

/**
 * What to look up a label file for.
 *
 * @property partNumber ECU part number without separators, e.g. `1K0035274B`
 * @property address diagnostic address of the control module, e.g. `0x37` for navigation
 * @property hardwarePartNumber hardware part number, used by redirect rules marked `HW`
 * @property subsystemPartNumber part number of a sub-system (slave) module, used by rules marked `SL`
 * @property subsystemIndex zero-based index of that sub-system; makes the lookup start from the sub-system
 */
data class LabelQuery(
    val partNumber: String,
    val address: Int,
    val hardwarePartNumber: String? = null,
    val subsystemPartNumber: String? = null,
    val subsystemIndex: Int? = null,
)

/**
 * Finds the label file for an ECU, the way the Deep OBD app does it:
 *
 * 1. a file named after the part number (`1k0-035-274-b.ldat`, then `1k0-035-274.ldat`);
 * 2. otherwise a per-address *redirect file* named `<first two characters of the part number>-<address>`
 *    (`1k-37.ldat`) whose `REDIRECT` lines list part-number patterns and the file to use for each.
 *
 * Patterns may end in `?` wildcards, which make the match a prefix match.
 *
 * @param labelsDir the `labels` directory; its direct sub-directories are searched as well
 */
class LabelResolver(private val labelsDir: File) {
    fun resolve(query: LabelQuery): File? {
        val full = PartNumber.of(query.partNumber)
        if (full.fullName.isEmpty()) return null
        val hardware = PartNumber.of(query.hardwarePartNumber)
        val subsystem = PartNumber.of(query.subsystemPartNumber)

        val dirs = (labelsDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()) + labelsDir
        val (file, isRedirectFile) = findStartFile(dirs, query, full, subsystem) ?: return null

        for (redirect in LabelFile.read(file).redirects) {
            val target = changeExtension(redirect.target, ".ldat").lowercase()
            val matched = if (isRedirectFile) {
                val source = when (redirect.source) {
                    "HW" -> hardware.fullName
                    "SL" -> subsystem.fullName
                    else -> full.fullName
                }
                source.isNotEmpty() && wildcardToRegex(redirect.pattern).containsMatchIn(source)
            } else {
                redirect.arguments.any { full.fullName.equals(full.baseName + it, ignoreCase = true) }
            }
            if (!matched) continue
            dirs.map { File(it, target) }.firstOrNull { it.isFile }?.let { return it }
        }
        return if (isRedirectFile) null else file
    }

    private fun findStartFile(dirs: List<File>, q: LabelQuery, full: PartNumber, subsystem: PartNumber): Pair<File, Boolean>? {
        val usesSubsystem = q.subsystemIndex != null
        if (!usesSubsystem) {
            for (dir in dirs) {
                for (name in listOf(full.fullName, full.baseName)) {
                    val f = File(dir, name.lowercase() + ".ldat")
                    if (f.isFile) return f to false
                }
            }
        }
        val partNumber = (if (usesSubsystem) q.subsystemPartNumber else q.partNumber).orEmpty()
        if (partNumber.length < 2) return null
        var redirectName = partNumber.substring(0, 2) + "-" + hex2(q.address)
        q.subsystemIndex?.let { redirectName += "-" + hex2(it + 1) }
        for (dir in dirs) {
            val f = File(dir, redirectName.lowercase() + ".ldat")
            if (f.isFile) return f to true
        }
        return null
    }

    /** `1K0035274B` -> base name `1K0-035-274`, full name `1K0-035-274-B`; shorter than 9 characters -> empty. */
    internal class PartNumber(val baseName: String, val fullName: String) {
        companion object {
            fun of(partNumber: String?): PartNumber {
                if (partNumber == null || partNumber.length < 9) return PartNumber("", "")
                val base = partNumber.substring(0, 3) + "-" + partNumber.substring(3, 6) + "-" + partNumber.substring(6, 9)
                val full = if (partNumber.length > 9) base + "-" + partNumber.substring(9) else base
                return PartNumber(base, full)
            }
        }
    }

    internal companion object {
        private fun hex2(value: Int): String = value.toString(16).uppercase().padStart(2, '0')

        private fun changeExtension(fileName: String, extension: String): String {
            val dot = fileName.lastIndexOf('.')
            return (if (dot > fileName.lastIndexOf('/').coerceAtLeast(fileName.lastIndexOf('\\'))) fileName.substring(0, dot) else fileName) + extension
        }

        /**
         * `?` matches any single character. Trailing `?`s (and a `-` right before them) are optional,
         * so `1K0-035-274-???` also matches `1K0-035-274` and `1K0-035-274-B`.
         */
        internal fun wildcardToRegex(pattern: String): Regex {
            var body = pattern.trimEnd('?')
            val hadTrailingWildcards = body.length != pattern.length
            if (hadTrailingWildcards) body = body.trimEnd('-')
            val regex = StringBuilder("^")
            for (c in body) regex.append(if (c == '?') "." else Regex.escape(c.toString()))
            regex.append(if (hadTrailingWildcards) ".*$" else "$")
            return Regex(regex.toString(), RegexOption.IGNORE_CASE)
        }
    }
}
