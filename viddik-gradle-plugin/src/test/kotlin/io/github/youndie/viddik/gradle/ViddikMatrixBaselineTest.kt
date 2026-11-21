package io.github.youndie.viddik.gradle

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViddikMatrixBaselineTest {
    private val root: File = Files.createTempDirectory("viddik-baseline-task").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun capture(
        dir: File,
        sdk: Int,
        density: String,
        vararg pngs: String,
    ) {
        dir.mkdirs()
        File(
            dir,
            CAPTURE_MARKER,
        ).writeText("{\"sdkInt\":$sdk,\"release\":\"x\",\"model\":\"x\",\"density\":$density}\n")
        pngs.forEach { File(dir, it).writeText("png") }
    }

    @Test
    fun `each kept level becomes a flat directory named by level and density, replacing the last one`() {
        val managed = File(root, "managed")
        val connected = File(root, "connected")
        capture(File(managed, "api34"), 34, "2.625", "A.png", "B.png")
        // A connected run keeps AGP's nesting.
        capture(File(connected, "api26/emulator-5554/viddik"), 26, "3.0", "A.png")
        File(connected, "api26/emulator-5554/viddik/$FILTER_FILE").writeText("A\n")
        val into = File(root, "baselines")
        File(into, "api34-2.625/Stale.png").apply { parentFile.mkdirs() }.writeText("old")

        val written = writeBaselines(listOf(managed, connected), into, emptySet())

        assertEquals(setOf("api34-2.625", "api26-3"), written.map { it.key }.toSet())
        assertTrue(File(into, "api34-2.625/B.png").isFile)
        assertFalse(File(into, "api34-2.625/Stale.png").exists(), "a level's baseline is replaced whole")
        assertTrue(File(into, "api26-3/A.png").isFile, "nested captures land flat")
        assertTrue(written.single { it.key == "api26-3" }.filtered)
    }

    @Test
    fun `only the asked levels are written`() {
        val managed = File(root, "managed")
        capture(File(managed, "api34"), 34, "2.625", "A.png")
        capture(File(managed, "api36"), 36, "2.625", "A.png")

        val written = writeBaselines(listOf(managed), File(root, "baselines"), setOf(36))

        assertEquals(listOf("api36-2.625"), written.map { it.key })
    }
}
