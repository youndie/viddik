package ru.workinprogress.viddik.core

import org.junit.jupiter.api.DynamicTest
import ru.workinprogress.viddik.annotations.ViddikComponent
import java.io.File
import javax.imageio.ImageIO

private const val RECORD_MODE_ENV = "VIDDIK_RECORD_MODE"
private const val SNAPSHOTS_DIR_PROPERTY = "viddik.snapshotsDir"
private const val REPORTS_DIR_PROPERTY = "viddik.reportsDir"
private const val DEFAULT_SNAPSHOTS_DIR = "src/desktopTest/snapshots"
private const val DEFAULT_REPORTS_DIR = "build/reports/screenshots"

object ViddikEngine {
    private val recordMode: Boolean
        get() = System.getenv(RECORD_MODE_ENV)?.toBooleanStrictOrNull() == true

    fun verify(
        component: ViddikComponent,
        snapshotsDir: File = File(System.getProperty(SNAPSHOTS_DIR_PROPERTY) ?: DEFAULT_SNAPSHOTS_DIR),
        reportsDir: File = File(System.getProperty(REPORTS_DIR_PROPERTY) ?: DEFAULT_REPORTS_DIR),
    ) {
        val fileName = fileNameFor(component)
        val goldenFile = File(snapshotsDir, fileName)
        val actual = captureComposable(width = component.width, height = component.height, content = component.content)

        if (recordMode) {
            snapshotsDir.mkdirs()
            ImageIO.write(actual, "png", goldenFile)
            return
        }

        if (!goldenFile.exists()) {
            error(
                "No golden snapshot for ${component.group}/${component.name} at ${goldenFile.path}. " +
                    "Run with $RECORD_MODE_ENV=true to record it.",
            )
        }

        val expected = ImageIO.read(goldenFile)
        val diff = ImageDiffer.diff(expected, actual)
        if (!diff.matches) {
            reportsDir.mkdirs()
            val diffFile = File(reportsDir, fileName.removeSuffix(".png") + "_DIFF.png")
            ImageIO.write(diff.diffImage, "png", diffFile)
            error(
                "Screenshot mismatch for ${component.group}/${component.name}: " +
                    "${diff.mismatchedPixels}/${diff.totalPixels} px differ (${"%.2f".format(diff.mismatchPercent)}%). " +
                    "Diff saved to ${diffFile.path}",
            )
        }
    }

    fun dynamicTests(components: List<ViddikComponent>): List<DynamicTest> =
        components.map { component ->
            DynamicTest.dynamicTest("${component.group} - ${component.name}") { verify(component) }
        }

    private fun fileNameFor(component: ViddikComponent): String {
        val safe = "${component.group}_${component.name}".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return "$safe.png"
    }
}
