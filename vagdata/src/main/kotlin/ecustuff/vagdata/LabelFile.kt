package ecustuff.vagdata

import java.io.File

/** One meaningful line of a `.ldat` label file. */
sealed interface LabelRecord {
    /** 1-based line number in the decrypted text. */
    val lineNumber: Int
}

/**
 * `REDIRECT,<target file>,<pattern>[,<source>...]`
 *
 * Maps an ECU part number to a more specific label file. [target] is a file name from the original
 * label package (extension `.LBL`/`.CLB`); the data files use `.ldat` instead. [arguments] holds
 * everything after the target: first the part-number pattern, then optionally `HW` or `SL`.
 */
data class Redirect(
    override val lineNumber: Int,
    val target: String,
    val arguments: List<String>,
) : LabelRecord {
    val pattern: String get() = arguments.first()

    /** `HW` / `SL` selects the hardware / subsystem part number instead of the main one. */
    val source: String? get() = arguments.getOrNull(1)?.takeIf { it.equals("HW", true) || it.equals("SL", true) }?.uppercase()
}

enum class GroupKind { MEASURING, ADAPTATION, BASIC_SETTING }

/**
 * Measuring blocks (`<group>,<field>,<text>...`), adaptation channels (`A<channel>,<index>,<text>...`)
 * and basic settings (`B<group>,<index>,<text>...`). [index] 0 is normally the block's own title and
 * 1..4 describe its fields; [texts] are the remaining comma separated columns (name, unit, range ...).
 */
data class GroupLabel(
    override val lineNumber: Int,
    val kind: GroupKind,
    val group: Int,
    val index: Int,
    val texts: List<String>,
) : LabelRecord

enum class SectionKind { LOGIN, SETTINGS, CODING }

/** `L<n>,<text>`, `S<n>,<text>` and `C<n>,<text>` lines: numbered free text for login, settings and coding help. */
data class SectionLabel(
    override val lineNumber: Int,
    val kind: SectionKind,
    val index: Int,
    val texts: List<String>,
) : LabelRecord

/**
 * Long coding: `LC,<byte>,<bits>,[<value>,]<text>`.
 *
 * - single bit: `LC,3,6,text` -> [bitMin] == [bitMax] == 6
 * - bit range with value: `LC,1,0~7,05,text` (`-` is also accepted as separator) -> [value] is hexadecimal
 * - `LC,3,2=,text` is a numbered description line for the byte -> [lineIndex]
 */
data class LongCodingLabel(
    override val lineNumber: Int,
    val byteIndex: Int,
    val bitMin: Int?,
    val bitMax: Int?,
    val value: Int?,
    val lineIndex: Int?,
    val text: String,
) : LabelRecord

/** Anything else: file metadata (`O`, `V`, `SN`, `W`), chassis tables, vehicle lines, `RJ` ... Never dropped. */
data class OtherRecord(
    override val lineNumber: Int,
    val fields: List<String>,
) : LabelRecord {
    val tag: String get() = fields.first()
}

/** A parsed `.ldat` label file. */
class LabelFile(val name: String, val records: List<LabelRecord>) {
    val redirects: List<Redirect> get() = records.filterIsInstance<Redirect>()

    fun measuringBlock(group: Int): List<GroupLabel> = group(GroupKind.MEASURING, group)
    fun adaptationChannel(channel: Int): List<GroupLabel> = group(GroupKind.ADAPTATION, channel)
    fun basicSetting(group: Int): List<GroupLabel> = group(GroupKind.BASIC_SETTING, group)
    fun longCoding(): List<LongCodingLabel> = records.filterIsInstance<LongCodingLabel>()

    private fun group(kind: GroupKind, group: Int): List<GroupLabel> =
        records.filterIsInstance<GroupLabel>().filter { it.kind == kind && it.group == group }

    companion object {
        /**
         * Parses decrypted text. Like the original app, everything from the first `;` on is a comment
         * (so label text cannot contain a semicolon), blank lines are skipped and columns are split
         * on every comma.
         */
        fun parse(text: String, name: String = ""): LabelFile {
            val records = ArrayList<LabelRecord>()
            text.lines().forEachIndexed { index, rawLine ->
                val cut = rawLine.indexOf(';')
                val line = if (cut >= 0) rawLine.substring(0, cut) else rawLine
                if (line.isBlank()) return@forEachIndexed
                val columns = line.split(',')
                records += classify(index + 1, columns.map { it.trim() }, columns)
            }
            return LabelFile(name, records)
        }

        fun read(file: File): LabelFile = parse(LabelFileReader.readText(file), file.name)

        private val NUMBER = Regex("\\d+")
        private val GROUP_TAG = Regex("([ABab]?)(\\d+)")
        private val SECTION_TAG = Regex("([LSClsc])(\\d+)")
        private val BIT_RANGE = Regex("(\\d+)[~-](\\d+)")

        private fun classify(line: Int, f: List<String>, raw: List<String>): LabelRecord {
            val tag = f[0]
            val other = OtherRecord(line, f)
            if (tag.equals("REDIRECT", ignoreCase = true)) {
                return if (f.size >= 3) Redirect(line, f[1], f.drop(2)) else other
            }
            if (tag.equals("LC", ignoreCase = true)) return longCoding(line, f, raw) ?: other

            GROUP_TAG.matchEntire(tag)?.let { m ->
                val index = f.getOrNull(1)?.toIntOrNull()
                val group = m.groupValues[2].toIntOrNull()
                if (index == null || group == null) return other
                val kind = when (m.groupValues[1].uppercase()) {
                    "A" -> GroupKind.ADAPTATION
                    "B" -> GroupKind.BASIC_SETTING
                    else -> GroupKind.MEASURING
                }
                return GroupLabel(line, kind, group, index, f.drop(2))
            }
            SECTION_TAG.matchEntire(tag)?.let { m ->
                if (f.size < 2) return other
                val index = m.groupValues[2].toIntOrNull() ?: return other
                val kind = when (m.groupValues[1].uppercase()) {
                    "L" -> SectionKind.LOGIN
                    "S" -> SectionKind.SETTINGS
                    else -> SectionKind.CODING
                }
                return SectionLabel(line, kind, index, f.drop(1))
            }
            return other
        }

        private fun longCoding(line: Int, f: List<String>, raw: List<String>): LongCodingLabel? {
            if (f.size < 4) return null
            val byteIndex = f[1].toIntOrNull() ?: return null
            val spec = f[2]
            // Rebuilt from the raw columns so a comma inside the label text keeps its spacing.
            fun text(from: Int) = raw.drop(from).joinToString(",").trim()

            if (spec.endsWith("=")) {
                val n = spec.dropLast(1).toIntOrNull() ?: return null
                return LongCodingLabel(line, byteIndex, null, null, null, n, text(3))
            }
            BIT_RANGE.matchEntire(spec)?.let { m ->
                val min = m.groupValues[1].toIntOrNull() ?: return null
                val max = m.groupValues[2].toIntOrNull() ?: return null
                val value = if (f.size >= 5) f[3].toIntOrNull(16) else null
                return LongCodingLabel(line, byteIndex, min, max, value, null, text(if (value != null) 4 else 3))
            }
            if (NUMBER.matches(spec)) {
                val bit = spec.toIntOrNull() ?: return null
                return LongCodingLabel(line, byteIndex, bit, bit, null, null, text(3))
            }
            return null
        }
    }
}
