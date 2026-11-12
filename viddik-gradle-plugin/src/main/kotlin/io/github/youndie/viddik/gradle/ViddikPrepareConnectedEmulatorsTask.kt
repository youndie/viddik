package io.github.youndie.viddik.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Gets an emulator ready for a connected run to pull captures from it.
 *
 * AGP finds the directory it passes to the capture, and pulls from afterwards, by asking MediaStore for the
 * path of `/sdcard/Android`. On the API 27 image that row is not there at all until somebody adds it, and
 * without it the run is green and pulls nothing. So this adds it, where it is missing and on an emulator:
 * a phone on the same adb is never written to, whatever `ANDROID_SERIAL` says.
 *
 * It also notes, in [pending], which levels the run is for, so that a run which never reaches the device
 * marks them; see [ViddikKeepConnectedCapturesTask].
 */
@DisableCachingByDefault(because = "It changes the state of a device.")
public abstract class ViddikPrepareConnectedEmulatorsTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Internal
        public abstract val adb: RegularFileProperty

        @get:Internal
        public abstract val androidSerial: Property<String>

        /** The kept connected directory's [PENDING_DIR]. */
        @get:Internal
        public abstract val pending: DirectoryProperty

        @TaskAction
        public fun prepare() {
            val emulators = connectedEmulators(androidSerial.orNull, adb("devices"))
            val pendingDir = pending.get().asFile
            pendingDir.deleteRecursively()
            pendingDir.mkdirs()
            pendingDir.resolve(RUN_STARTED).writeText("")
            emulators.forEach { serial ->
                adb("-s", serial, "shell", "getprop", "ro.build.version.sdk").toIntOrNull()?.let {
                    pendingDir.resolve("api$it").writeText("")
                }
            }
            emulators.forEach { serial ->
                if (indexed(serial)) return@forEach
                logger.lifecycle("viddik matrix: $serial has not indexed /sdcard/Android; adding it to MediaStore.")
                adb("-s", serial, "shell", MEDIA_STORE_INSERT)
                if (!indexed(serial)) {
                    logger.warn(
                        "viddik matrix: $serial still has no /sdcard/Android in MediaStore, so this run may pull " +
                            "nothing back (docs/android-matrix.md, \"Levels below 29\").",
                    )
                }
            }
        }

        private fun indexed(serial: String): Boolean = adb("-s", serial, "shell", MEDIA_STORE_QUERY).startsWith("Row")

        /** Its output, and never a failure: a device that does not answer is the test task's to report. */
        private fun adb(vararg args: String): String {
            val output = ByteArrayOutputStream()
            exec.exec { spec ->
                spec.commandLine(listOf(adb.get().asFile.path) + args)
                spec.standardOutput = output
                spec.errorOutput = output
                spec.isIgnoreExitValue = true
            }
            return output.toString(Charsets.UTF_8.name()).trim()
        }
    }

/**
 * The emulators a connected run is about to use: those `ANDROID_SERIAL` names (a comma-separated list, as
 * AGP reads it), or without it, every one `adb devices` lists as ready.
 */
internal fun connectedEmulators(
    androidSerial: String?,
    adbDevices: String,
): List<String> {
    val serials =
        if (androidSerial.isNullOrBlank()) {
            adbDevices
                .lineSequence()
                .map { it.trim().split(Regex("\\s+")) }
                .filter { it.size == 2 && it[1] == "device" }
                .map { it[0] }
                .toList()
        } else {
            androidSerial.split(',').map { it.trim() }
        }
    return serials.filter { it.startsWith("emulator-") }
}

// Spaces escaped for the device's shell rather than quoted: a double quote did not survive the way to adb on
// Windows, the query failed, and every emulator looked as if it had not indexed anything.
private const val MEDIA_STORE_QUERY =
    "content query --uri content://media/external/file --projection _data --where _data\\ LIKE\\ \\'%/Android\\'"
private const val MEDIA_STORE_INSERT =
    "content insert --uri content://media/external/file --bind _data:s:/storage/emulated/0/Android"
