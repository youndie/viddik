package io.github.youndie.viddik.gradle

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViddikAndroidMatrixNamesTest {
    private fun File.capture(
        level: Int,
        device: String,
        vararg files: String,
    ) {
        val capture = resolve("connected/$device/viddik").apply { mkdirs() }
        capture.resolve("device.json").writeText("{\"sdkInt\":$level}")
        files.forEach { capture.resolve(it).writeText(device) }
    }

    @Test
    fun `a connected run keeps the levels before it, and replaces the whole of its own`() {
        val root = createTempDirectory("viddik-keep").toFile()
        try {
            val pulled = root.resolve("pulled")
            val kept = root.resolve("kept")

            fun run(
                level: Int,
                device: String,
                vararg files: String,
            ): Set<Int> {
                pulled.deleteRecursively()
                pulled.capture(level, device, *files)
                return keepCaptures(pulled, kept)
            }

            assertEquals(setOf(24), run(24, "emulator-5554 - 7.0", "a.png", "stale.png"))
            assertEquals(setOf(26), run(26, "emulator-5556 - 8.0", "a.png"))
            // The same level on another port: a directory of another name, and still the same level.
            assertEquals(setOf(24), run(24, "emulator-5590 - 7.0", "a.png"))

            assertEquals(
                "emulator-5590 - 7.0",
                kept.resolve("api24/connected/emulator-5590 - 7.0/viddik/a.png").readText(),
            )
            assertEquals(
                "emulator-5556 - 8.0",
                kept.resolve("api26/connected/emulator-5556 - 8.0/viddik/a.png").readText(),
            )
            assertFalse(kept.resolve("api24/connected/emulator-5554 - 7.0").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.run(
        level: Int,
        density: String,
        expected: List<String>,
        pngs: List<String> = expected,
        filter: String? = null,
        failures: String = "",
        unfit: String = "",
    ): File =
        apply {
            mkdirs()
            resolve("device.json").writeText("{\"sdkInt\":$level,\"model\":\"m\",\"density\":$density}")
            resolve("fixtures.txt").writeText(expected.joinToString("") { "$it\t${it.removeSuffix(".png")}\n" })
            pngs.forEach { resolve(it).writeText(parentFile.name) }
            filter?.let { resolve("filter.txt").writeText("$it\n") }
            resolve("failures.txt").writeText(failures)
            resolve("does-not-fit.txt").writeText(unfit)
        }

    @Test
    fun `a filtered run adds to what the level has, and a full one replaces it`() {
        val root = createTempDirectory("viddik-append").toFile()
        try {
            val pulled = root.resolve("pulled")
            val kept = root.resolve("kept")
            pulled
                .resolve("full/viddik")
                .run(24, "2.625", listOf("a.png", "b.png", "c.png"), failures = "c.png\tthrew\n")
            keepCaptures(pulled, kept)
            val level = kept.resolve("api24/full/viddik")
            kept.resolve("api24/$PULLED_NOTHING_MARKER").writeText("")

            // Two of the three again, one of them now too large; c no longer throws.
            pulled.deleteRecursively()
            pulled.resolve("quick/viddik").run(
                24,
                "2.625",
                listOf("b.png", "c.png", "d.png"),
                pngs = listOf("c.png", "d.png"),
                filter = "b|c|d",
                unfit = "b.png\ttoo large\n",
            )
            assertEquals(setOf(24), keepCaptures(pulled, kept))

            assertEquals("full", level.resolve("a.png").readText(), "a capture the run did not have stays")
            assertFalse(level.resolve("b.png").exists(), "a capture the run did not take is gone, not stale")
            assertEquals("quick", level.resolve("c.png").readText())
            assertTrue(level.resolve("d.png").isFile)
            assertEquals(
                listOf("a.png", "b.png", "c.png", "d.png"),
                level.resolve("fixtures.txt").readLines().map { it.substringBefore('\t') },
            )
            assertEquals("", level.resolve("failures.txt").readText())
            assertEquals("b.png\ttoo large\n", level.resolve("does-not-fit.txt").readText())
            assertFalse(kept.resolve("api24/$PULLED_NOTHING_MARKER").exists())

            // Another density is another size of every fixture: it replaces, filtered or not.
            pulled.deleteRecursively()
            pulled.resolve("other/viddik").run(24, "3.5", listOf("a.png"), filter = "a")
            keepCaptures(pulled, kept)
            assertFalse(level.exists())
            assertTrue(kept.resolve("api24/other/viddik/a.png").isFile)

            // And a full run replaces whatever the level had.
            pulled.deleteRecursively()
            pulled.resolve("again/viddik").run(24, "3.5", listOf("e.png"))
            keepCaptures(pulled, kept)
            assertFalse(kept.resolve("api24/other").exists())
            assertTrue(kept.resolve("api24/again/viddik/e.png").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a managed level appends a filtered run the same way`() {
        val root = createTempDirectory("viddik-append-managed").toFile()
        try {
            val pulled = root.resolve("pulled")
            val kept = root.resolve("kept")
            pulled.resolve("a/viddik").run(34, "2.625", listOf("a.png", "b.png"))
            assertTrue(keepManagedCapture(listOf(pulled), 34, 0L, kept))
            pulled.deleteRecursively()
            pulled.resolve("b/viddik").run(34, "2.625", listOf("b.png"), filter = "b")
            assertTrue(keepManagedCapture(listOf(pulled), 34, 0L, kept))

            assertEquals("a", kept.resolve("api34/a.png").readText())
            assertEquals(2, kept.resolve("api34/fixtures.txt").readLines().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the levels a connected run was for are read back, and nothing else`() {
        val root = createTempDirectory("viddik-pending").toFile()
        try {
            root.resolve("api24").writeText("")
            root.resolve("api26").writeText("")
            root.resolve("notes.txt").writeText("")

            assertEquals(setOf(24, 26), pendingLevels(root))
            assertEquals(emptySet(), pendingLevels(root.resolve("missing")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `results older than the run's start say the test never ran`() {
        val root = createTempDirectory("viddik-stale").toFile()
        try {
            val results = root.resolve("results").apply { mkdirs() }
            val old = results.resolve("TEST-emulator.xml").apply { writeText("<testcase name=\"captureAll\" />") }
            old.setLastModified(old.lastModified() - 60_000L)

            assertTrue(testRanIn(results))
            assertFalse(testRanIn(results, since = old.lastModified() + 1000L))
            assertFalse(captureRanIn(results, since = old.lastModified() + 1000L))

            // The same for what AGP pulled: a run that never reached the device left the last run's pull.
            root.resolve("pulled").capture(26, "emulator-5556 - 8.0", "a.png")
            val pull = root.resolve("pulled/connected/emulator-5556 - 8.0/viddik/device.json")
            pull.setLastModified(pull.lastModified() - 60_000L)
            assertEquals(
                emptySet(),
                keepCaptures(root.resolve("pulled"), root.resolve("kept"), pull.lastModified() + 1),
            )
            assertFalse(root.resolve("kept").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a run that pulled nothing leaves what was kept`() {
        val root = createTempDirectory("viddik-keep").toFile()
        try {
            root.resolve("kept/api24").capture(24, "a", "a.png")

            assertEquals(emptySet(), keepCaptures(root.resolve("nothing-here"), root.resolve("kept")))
            assertTrue(root.resolve("kept/api24/connected/a/viddik/a.png").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the results say whether the capture ran, and its logcat where it wrote`() {
        val root = createTempDirectory("viddik-results").toFile()
        try {
            val device = root.resolve("androidMain/emulator - 8.0").apply { mkdirs() }
            root.resolve("androidMain/TEST-other.xml").writeText("<testcase name=\"somethingElse\" />")
            assertFalse(captureRanIn(root))
            assertNull(whereCapturesWent(root))

            root.resolve("androidMain/TEST-emulator.xml").writeText("<testcase name=\"captureAll\" classname=\"x\" />")
            device
                .resolve("logcat-x.GeneratedViddikAndroidMatrix-captureAll.txt")
                .writeText("11-06 12:00:00.000 I/viddik: Captures go to /sdcard/somewhere/viddik\n")
            assertTrue(captureRanIn(root))
            assertEquals("/sdcard/somewhere/viddik", whereCapturesWent(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a managed level is kept under the level its device reported, and only from this run`() {
        val root = createTempDirectory("viddik-managed").toFile()
        try {
            val outputs = root.resolve("outputs")
            val intermediates = root.resolve("intermediates")
            val kept = root.resolve("kept")
            // What AGP did: API 31's run in the directory named after 36, and a week-old 36 beside it.
            outputs.resolve("androidmain/viddikApi36").capture(31, "x", "a.png")
            intermediates.resolve("viddikApi36AndroidDeviceTest").capture(36, "old", "a.png")
            val old = intermediates.resolve("viddikApi36AndroidDeviceTest/connected/old/viddik/device.json")
            old.setLastModified(old.lastModified() - 7 * 24 * 60 * 60 * 1000L)
            val since = old.lastModified() + 1000L

            assertTrue(keepManagedCapture(listOf(outputs, intermediates), 31, since, kept))
            assertTrue(kept.resolve("api31/a.png").isFile)
            assertFalse(keepManagedCapture(listOf(outputs, intermediates), 36, since, kept))
            assertFalse(kept.resolve("api36").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an empty pull is explained by where the device wrote`() {
        assertTrue(
            "AGP itself passed" in emptyPullExplanation("/storage/emulated/0/Android/data/p/files/test_data/viddik"),
        )
        assertTrue("its own fallback" in emptyPullExplanation("/storage/emulated/0/Android/data/p/files/viddik"))
        assertTrue("does not say" in emptyPullExplanation(null))
    }

    @Test
    fun `an empty pull is put on the level the capture named in logcat`() {
        val root = createTempDirectory("viddik-logcat").toFile()
        try {
            val results = root.resolve("results/connected/debug/emulator-5554")
            results.mkdirs()
            results.resolve("logcat-io.github.x.MatrixTest-captureAll.txt").writeText(
                "11-11 10:00:00.000 I viddik  : Capturing on API 27\n" +
                    "11-11 10:00:00.001 I viddik  : Captures go to /storage/emulated/0/Android/data/p/files/test_data/viddik\n",
            )

            assertEquals(27, levelInLogcat(root.resolve("results")))
            assertEquals(
                "/storage/emulated/0/Android/data/p/files/test_data/viddik",
                whereCapturesWent(root.resolve("results")),
            )
            markPulledNothing(root.resolve("kept/api27"))
            assertTrue(root.resolve("kept/api27/$PULLED_NOTHING_MARKER").isFile)
            assertNull(levelInLogcat(root.resolve("nothing")))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a keep task's name carries its level`() {
        assertEquals(34, keepLevel(keepTaskName(34)))
        assertNull(keepLevel("viddikKeepConnectedCaptures"))
    }

    @Test
    fun `only an emulator is ever prepared, whatever ANDROID_SERIAL names`() {
        val devices = "List of devices attached\nemulator-5554\tdevice\nemulator-5556\toffline\nphone-1\tdevice\n"

        assertEquals(listOf("emulator-5554"), connectedEmulators(null, devices))
        assertEquals(listOf("emulator-5556"), connectedEmulators("emulator-5556", devices))
        assertEquals(listOf("emulator-5556"), connectedEmulators("phone-1, emulator-5556", devices))
        assertEquals(emptyList(), connectedEmulators("phone-1", devices))
    }

    @Test
    fun `a level is read off a managed device's task name`() {
        assertEquals(34, deviceLevel("viddikApi34AndroidDeviceTest"))
        assertEquals(29, deviceLevel("viddikApi29Setup"))
    }

    @Test
    fun `other tasks have no level, the group's included`() {
        assertNull(deviceLevel("viddikMatrixGroupAndroidDeviceTest"))
        assertNull(deviceLevel("viddikMatrixGroupSetup"))
        assertNull(deviceLevel("viddikApi34Foo"))
        assertNull(deviceLevel("connectedAndroidDeviceTest"))
    }

    @Test
    fun `every default level can be captured by the default task`() {
        assertTrue(DEFAULT_API_LEVELS.all { it >= MIN_MANAGED_API }, "$DEFAULT_API_LEVELS, floor $MIN_MANAGED_API")
    }
}
