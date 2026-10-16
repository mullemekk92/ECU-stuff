package ecustuff.vagdata

import java.io.File

/**
 * One language's data directory, e.g. `.../Vag/en`:
 *
 * ```
 * codes.cdat      code -> text table
 * labels/         *.ldat label files (flat, or one level of sub-directories)
 * uds_ev/         *.uds UDS ECU descriptions (and a few shared tables)
 * ```
 */
class VagDataDir(val root: File) {
    val labelsDir: File get() = File(root, "labels")
    val udsDir: File get() = File(root, "uds_ev")

    fun loadCodes(): CodeTable {
        val candidates = root.listFiles { f -> f.isFile && f.name.startsWith("code", true) && f.name.endsWith(".cdat", true) }.orEmpty()
        require(candidates.size == 1) { "expected exactly one code*.cdat in $root, found ${candidates.size}" }
        return CodeTable.read(candidates.single())
    }

    fun labelResolver(): LabelResolver = LabelResolver(labelsDir)

    fun resolveLabelFile(query: LabelQuery): LabelFile? = labelResolver().resolve(query)?.let { LabelFile.read(it) }

    fun labelFiles(): List<File> = listFiles(labelsDir, ".ldat")

    fun udsFiles(): List<File> = listFiles(udsDir, ".uds")

    fun readUdsFile(name: String): UdsFile = UdsFile.read(File(udsDir, name))

    private fun listFiles(dir: File, extension: String): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(extension, true) }.orEmpty().sortedBy { it.name }
}
