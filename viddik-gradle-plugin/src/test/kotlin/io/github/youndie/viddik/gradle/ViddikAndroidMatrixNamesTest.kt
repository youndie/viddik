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
            ): Int {
                pulled.deleteRecursively()
                pulled.capture(level, device, *files)
                return keepCaptures(pulled, kept)
            }

            assertEquals(1, run(24, "emulator-5554 - 7.0", "a.png", "stale.png"))
            assertEquals(1, run(26, "emulator-5556 - 8.0", "a.png"))
            // The same level on another port: a directory of another name, and still the same level.
            assertEquals(1, run(24, "emulator-5590 - 7.0", "a.png"))

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

    @Test
    fun `a run that pulled nothing leaves what was kept`() {
        val root = createTempDirectory("viddik-keep").toFile()
        try {
            root.resolve("kept/api24").capture(24, "a", "a.png")

            assertEquals(0, keepCaptures(root.resolve("nothing-here"), root.resolve("kept")))
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
