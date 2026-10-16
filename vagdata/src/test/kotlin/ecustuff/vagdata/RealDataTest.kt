package ecustuff.vagdata

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs against your own downloaded data and is skipped when `ECU_VAG_DIR` is not set:
 *
 *     ECU_VAG_DIR=/path/to/Vag/en ./gradlew :vagdata:test --tests '*RealDataTest*' -i
 *
 * Nothing from that directory is copied into the repository.
 */
class RealDataTest {
    private val dir: VagDataDir? = System.getenv("ECU_VAG_DIR")?.takeIf { it.isNotBlank() }?.let { VagDataDir(File(it)) }

    private fun requireData(): VagDataDir {
        assumeTrue(dir != null, "ECU_VAG_DIR is not set")
        return dir!!
    }

    @Test
    fun `codes table loads without rejected lines`() {
        val codes = requireData().loadCodes()
        println("codes: ${codes.size} entries, ${codes.rejectedLines.size} rejected")
        assertEquals(emptyList(), codes.rejectedLines)
        assertTrue(codes.size > 1000)
    }

    @Test
    fun `every label file decrypts and every long-coding line parses`() {
        val data = requireData()
        val files = data.labelFiles() + File(data.udsDir, "chassis.ldat").takeIf { it.isFile }.let { listOfNotNull(it) }
        val failures = ArrayList<String>()
        val kinds = sortedMapOf<String, Int>()
        val unparsedLc = ArrayList<String>()
        for (file in files) {
            val parsed = try {
                LabelFile.read(file)
            } catch (e: Exception) {
                failures += "${file.name}: $e"
                continue
            }
            for (r in parsed.records) {
                kinds.merge(r::class.simpleName!!, 1, Int::plus)
                if (r is OtherRecord && r.tag.equals("LC", true)) unparsedLc += "${file.name}:${r.lineNumber} ${r.fields}"
            }
        }
        println("label files: ${files.size}, records by type: $kinds")
        println("unparsed LC lines: ${unparsedLc.size} ${unparsedLc.take(5)}")
        assertEquals(emptyList(), failures)
        assertEquals(emptyList(), unparsedLc)
    }

    @Test
    fun `every uds file decrypts and splits into segments`() {
        val data = requireData()
        val failures = ArrayList<String>()
        val segmentCounts = sortedMapOf<String, Int>()
        val files = data.udsFiles()
        for (file in files) {
            try {
                UdsFile.read(file).segments.forEach { (name, rows) -> segmentCounts.merge(name, rows.size, Int::plus) }
            } catch (e: Exception) {
                failures += "${file.name}: $e"
            }
        }
        println("uds files: ${files.size}, rows per segment: $segmentCounts")
        assertEquals(emptyList(), failures)
        assertTrue(files.isNotEmpty())
    }

    @Test
    fun `a real part number resolves through its redirect file`() {
        val data = requireData()
        // 1K-37.ldat redirects 1K0-035-274-??? to 1k0-035-27x-37.ldat
        val resolved = data.labelResolver().resolve(LabelQuery("1K0035274B", address = 0x37))
        println("resolved: $resolved")
        assertEquals("1k0-035-27x-37.ldat", resolved?.name)
        assertTrue(LabelFile.read(resolved!!).longCoding().isNotEmpty())
    }
}
